/**
 * Copyright (C) 2014-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.impl

import akka.dispatch.ExecutionContexts
import akka.stream.ActorAttributes.SupervisionStrategy
import akka.stream.Supervision.{ Stop, stoppingDecider }
import akka.stream.impl.QueueSink.{ Output, Pull }
import akka.stream.impl.fusing.GraphInterpreter
import akka.{ Done, NotUsed, annotation }
import akka.actor.{ Actor, ActorRef, Props }
import akka.stream.Attributes.InputBuffer
import akka.stream._
import akka.stream.impl.Stages.DefaultAttributes
import akka.stream.impl.StreamLayout.AtomicModule
import java.util.concurrent.atomic.AtomicReference
import java.util.function.BiConsumer

import akka.actor.{ ActorRef, Props }
import akka.stream.Attributes.InputBuffer
import akka.stream._
import akka.stream.stage._
import org.reactivestreams.{ Publisher, Subscriber }

import scala.annotation.unchecked.uncheckedVariance
import scala.collection.immutable
import scala.concurrent.{ ExecutionContext, Future, Promise }
import scala.language.postfixOps
import scala.util.control.NonFatal
import scala.util.{ Failure, Success, Try }
import akka.stream.scaladsl.{ Sink, SinkQueue, SinkQueueWithCancel, Source }
import java.util.concurrent.CompletionStage

import scala.compat.java8.FutureConverters._
import scala.compat.java8.OptionConverters._
import java.util.Optional

import akka.annotation.{ DoNotInherit, InternalApi }
import akka.event.Logging
import akka.util.OptionVal

import scala.collection.generic.CanBuildFrom

/**
 * INTERNAL API
 */
@DoNotInherit private[akka] abstract class SinkModule[-In, Mat](val shape: SinkShape[In]) extends AtomicModule[SinkShape[In], Mat] {

  /**
   * Create the Subscriber or VirtualPublisher that consumes the incoming
   * stream, plus the materialized value. Since Subscriber and VirtualPublisher
   * do not share a common supertype apart from AnyRef this is what the type
   * union devolves into; unfortunately we do not have union types at our
   * disposal at this point.
   */
  def create(context: MaterializationContext): (AnyRef, Mat)

  def attributes: Attributes

  override def traversalBuilder: TraversalBuilder =
    LinearTraversalBuilder.fromModule(this, attributes).makeIsland(SinkModuleIslandTag)

  // This is okay since we the only caller of this method is right below.
  // TODO: Remove this, no longer needed
  protected def newInstance(s: SinkShape[In] @uncheckedVariance): SinkModule[In, Mat]

  protected def amendShape(attr: Attributes): SinkShape[In] = {
    val thisN = traversalBuilder.attributes.nameOrDefault(null)
    val thatN = attr.nameOrDefault(null)

    if ((thatN eq null) || thisN == thatN) shape
    else shape.copy(in = Inlet(thatN + ".in"))
  }

  protected def label: String = Logging.simpleName(this)
  final override def toString: String = f"$label [${System.identityHashCode(this)}%08x]"

}

/**
 * INTERNAL API
 * Holds the downstream-most [[org.reactivestreams.Publisher]] interface of the materialized flow.
 * The stream will not have any subscribers attached at this point, which means that after prefetching
 * elements to fill the internal buffers it will assert back-pressure until
 * a subscriber connects and creates demand for elements to be emitted.
 */
@InternalApi private[akka] class PublisherSink[In](val attributes: Attributes, shape: SinkShape[In]) extends SinkModule[In, Publisher[In]](shape) {

  /*
   * This method is the reason why SinkModule.create may return something that is
   * not a Subscriber: a VirtualPublisher is used in order to avoid the immediate
   * subscription a VirtualProcessor would perform (and it also saves overhead).
   */
  override def create(context: MaterializationContext): (AnyRef, Publisher[In]) = {
    val proc = new VirtualPublisher[In]
    (proc, proc)
  }

  override protected def newInstance(shape: SinkShape[In]): SinkModule[In, Publisher[In]] = new PublisherSink[In](attributes, shape)
  override def withAttributes(attr: Attributes): SinkModule[In, Publisher[In]] = new PublisherSink[In](attr, amendShape(attr))
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] final class FanoutPublisherSink[In](
  val attributes: Attributes,
  shape:          SinkShape[In])
  extends SinkModule[In, Publisher[In]](shape) {

  override def create(context: MaterializationContext): (Subscriber[In], Publisher[In]) = {
    val actorMaterializer = ActorMaterializerHelper.downcast(context.materializer)
    val impl = actorMaterializer.actorOf(
      context,
      FanoutProcessorImpl.props(context.effectiveAttributes, actorMaterializer.settings))
    val fanoutProcessor = new ActorProcessor[In, In](impl)
    impl ! ExposedPublisher(fanoutProcessor.asInstanceOf[ActorPublisher[Any]])
    // Resolve cyclic dependency with actor. This MUST be the first message no matter what.
    (fanoutProcessor, fanoutProcessor)
  }

  override protected def newInstance(shape: SinkShape[In]): SinkModule[In, Publisher[In]] =
    new FanoutPublisherSink[In](attributes, shape)

  override def withAttributes(attr: Attributes): SinkModule[In, Publisher[In]] =
    new FanoutPublisherSink[In](attr, amendShape(attr))
}

/**
 * INTERNAL API
 * Attaches a subscriber to this stream.
 */
@InternalApi private[akka] final class SubscriberSink[In](subscriber: Subscriber[In], val attributes: Attributes, shape: SinkShape[In]) extends SinkModule[In, NotUsed](shape) {

  override def create(context: MaterializationContext) = (subscriber, NotUsed)

  override protected def newInstance(shape: SinkShape[In]): SinkModule[In, NotUsed] = new SubscriberSink[In](subscriber, attributes, shape)
  override def withAttributes(attr: Attributes): SinkModule[In, NotUsed] = new SubscriberSink[In](subscriber, attr, amendShape(attr))
}

/**
 * INTERNAL API
 * A sink that immediately cancels its upstream upon materialization.
 */
@InternalApi private[akka] final class CancelSink(val attributes: Attributes, shape: SinkShape[Any]) extends SinkModule[Any, NotUsed](shape) {
  override def create(context: MaterializationContext): (Subscriber[Any], NotUsed) = (new CancellingSubscriber[Any], NotUsed)
  override protected def newInstance(shape: SinkShape[Any]): SinkModule[Any, NotUsed] = new CancelSink(attributes, shape)
  override def withAttributes(attr: Attributes): SinkModule[Any, NotUsed] = new CancelSink(attr, amendShape(attr))
}

/**
 * INTERNAL API
 * Creates and wraps an actor into [[org.reactivestreams.Subscriber]] from the given `props`,
 * which should be [[akka.actor.Props]] for an [[akka.stream.actor.ActorSubscriber]].
 */
@InternalApi private[akka] final class ActorSubscriberSink[In](props: Props, val attributes: Attributes, shape: SinkShape[In]) extends SinkModule[In, ActorRef](shape) {

  override def create(context: MaterializationContext) = {
    val subscriberRef = ActorMaterializerHelper.downcast(context.materializer).actorOf(context, props)
    (akka.stream.actor.ActorSubscriber[In](subscriberRef), subscriberRef)
  }

  override protected def newInstance(shape: SinkShape[In]): SinkModule[In, ActorRef] = new ActorSubscriberSink[In](props, attributes, shape)
  override def withAttributes(attr: Attributes): SinkModule[In, ActorRef] = new ActorSubscriberSink[In](props, attr, amendShape(attr))
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] final class ActorRefSink[In](ref: ActorRef, onCompleteMessage: Any, onFailureMessage: Throwable ⇒ Any,
                                                        val attributes: Attributes,
                                                        shape:          SinkShape[In]) extends SinkModule[In, NotUsed](shape) {

  override def create(context: MaterializationContext) = {
    val actorMaterializer = ActorMaterializerHelper.downcast(context.materializer)
    val maxInputBufferSize = context.effectiveAttributes.mandatoryAttribute[Attributes.InputBuffer].max
    val subscriberRef = actorMaterializer.actorOf(
      context,
      ActorRefSinkActor.props(ref, maxInputBufferSize, onCompleteMessage, onFailureMessage))
    (akka.stream.actor.ActorSubscriber[In](subscriberRef), NotUsed)
  }

  override protected def newInstance(shape: SinkShape[In]): SinkModule[In, NotUsed] =
    new ActorRefSink[In](ref, onCompleteMessage, onFailureMessage, attributes, shape)
  override def withAttributes(attr: Attributes): SinkModule[In, NotUsed] =
    new ActorRefSink[In](ref, onCompleteMessage, onFailureMessage, attr, amendShape(attr))
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] final class LastOptionStage[T] extends GraphStageWithMaterializedValue[SinkShape[T], Future[Option[T]]] {

  val in: Inlet[T] = Inlet("lastOption.in")

  override val shape: SinkShape[T] = SinkShape.of(in)

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes) = {
    val p: Promise[Option[T]] = Promise()
    (new GraphStageLogic(shape) with InHandler {
      private[this] var prev: T = null.asInstanceOf[T]

      override def preStart(): Unit = pull(in)

      def onPush(): Unit = {
        prev = grab(in)
        pull(in)
      }

      override def onUpstreamFinish(): Unit = {
        val head = prev
        prev = null.asInstanceOf[T]
        p.trySuccess(Option(head))
        completeStage()
      }

      override def onUpstreamFailure(ex: Throwable): Unit = {
        prev = null.asInstanceOf[T]
        p.tryFailure(ex)
        failStage(ex)
      }

      setHandler(in, this)
    }, p.future)
  }

  override def toString: String = "LastOptionStage"
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] final class HeadOptionStage[T] extends GraphStageWithMaterializedValue[SinkShape[T], Future[Option[T]]] {

  val in: Inlet[T] = Inlet("headOption.in")

  override val shape: SinkShape[T] = SinkShape.of(in)

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes) = {
    val p: Promise[Option[T]] = Promise()
    (new GraphStageLogic(shape) with InHandler {
      override def preStart(): Unit = pull(in)

      def onPush(): Unit = {
        p.trySuccess(Option(grab(in)))
        completeStage()
      }

      override def onUpstreamFinish(): Unit = {
        p.trySuccess(None)
        completeStage()
      }

      override def onUpstreamFailure(ex: Throwable): Unit = {
        p.tryFailure(ex)
        failStage(ex)
      }

      override def postStop(): Unit = {
        if (!p.isCompleted) p.failure(new AbruptStageTerminationException(this))
      }

      setHandler(in, this)
    }, p.future)
  }

  override def toString: String = "HeadOptionStage"
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] final class SeqStage[T, That](implicit cbf: CanBuildFrom[Nothing, T, That with immutable.Traversable[_]]) extends GraphStageWithMaterializedValue[SinkShape[T], Future[That]] {
  val in = Inlet[T]("seq.in")

  override def toString: String = "SeqStage"

  override val shape: SinkShape[T] = SinkShape.of(in)

  override protected def initialAttributes: Attributes = DefaultAttributes.seqSink

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes) = {
    val p: Promise[That] = Promise()
    val logic = new GraphStageLogic(shape) with InHandler {
      val buf = cbf()

      override def preStart(): Unit = pull(in)

      def onPush(): Unit = {
        buf += grab(in)
        pull(in)
      }

      override def onUpstreamFinish(): Unit = {
        val result = buf.result()
        p.trySuccess(result)
        completeStage()
      }

      override def onUpstreamFailure(ex: Throwable): Unit = {
        p.tryFailure(ex)
        failStage(ex)
      }

      override def postStop(): Unit = {
        if (!p.isCompleted) p.failure(new AbruptStageTerminationException(this))
      }

      setHandler(in, this)
    }

    (logic, p.future)
  }
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] object QueueSink {
  sealed trait Output[+T]
  final case class Pull[T](promise: Promise[Option[T]]) extends Output[T]
  case object Cancel extends Output[Nothing]
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] final class QueueSink[T]() extends GraphStageWithMaterializedValue[SinkShape[T], SinkQueueWithCancel[T]] {
  type Requested[E] = Promise[Option[E]]

  val in = Inlet[T]("queueSink.in")
  override def initialAttributes = DefaultAttributes.queueSink
  override val shape: SinkShape[T] = SinkShape.of(in)

  override def toString: String = "QueueSink"

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes) = {
    val stageLogic = new GraphStageLogic(shape) with InHandler with SinkQueueWithCancel[T] {
      type Received[E] = Try[Option[E]]

      val maxBuffer = inheritedAttributes.getAttribute(classOf[InputBuffer], InputBuffer(16, 16)).max
      require(maxBuffer > 0, "Buffer size must be greater than 0")

      var buffer: Buffer[Received[T]] = _
      var currentRequest: Option[Requested[T]] = None

      override def preStart(): Unit = {
        // Allocates one additional element to hold stream
        // closed/failure indicators
        buffer = Buffer(maxBuffer + 1, materializer)
        setKeepGoing(true)
        pull(in)
      }

      private val callback = getAsyncCallback[Output[T]] {
        case QueueSink.Pull(pullPromise) ⇒ currentRequest match {
          case Some(_) ⇒
            pullPromise.failure(new IllegalStateException("You have to wait for previous future to be resolved to send another request"))
          case None ⇒
            if (buffer.isEmpty) currentRequest = Some(pullPromise)
            else {
              if (buffer.used == maxBuffer) tryPull(in)
              sendDownstream(pullPromise)
            }
        }
        case QueueSink.Cancel ⇒ completeStage()
      }

      def sendDownstream(promise: Requested[T]): Unit = {
        val e = buffer.dequeue()
        promise.complete(e)
        e match {
          case Success(_: Some[_]) ⇒ //do nothing
          case Success(None)       ⇒ completeStage()
          case Failure(t)          ⇒ failStage(t)
        }
      }

      def enqueueAndNotify(requested: Received[T]): Unit = {
        buffer.enqueue(requested)
        currentRequest match {
          case Some(p) ⇒
            sendDownstream(p)
            currentRequest = None
          case None ⇒ //do nothing
        }
      }

      def onPush(): Unit = {
        enqueueAndNotify(Success(Some(grab(in))))
        if (buffer.used < maxBuffer) pull(in)
      }

      override def onUpstreamFinish(): Unit = enqueueAndNotify(Success(None))
      override def onUpstreamFailure(ex: Throwable): Unit = enqueueAndNotify(Failure(ex))

      setHandler(in, this)

      // SinkQueueWithCancel impl
      override def pull(): Future[Option[T]] = {
        val p = Promise[Option[T]]
        callback.invokeWithFeedback(Pull(p))
          .onFailure { case NonFatal(e) ⇒ p.tryFailure(e) }(akka.dispatch.ExecutionContexts.sameThreadExecutionContext)
        p.future
      }
      override def cancel(): Unit = {
        callback.invoke(QueueSink.Cancel)
      }
    }

    (stageLogic, stageLogic)
  }
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] final class SinkQueueAdapter[T](delegate: SinkQueueWithCancel[T]) extends akka.stream.javadsl.SinkQueueWithCancel[T] {
  import akka.dispatch.ExecutionContexts.{ sameThreadExecutionContext ⇒ same }
  def pull(): CompletionStage[Optional[T]] = delegate.pull().map(_.asJava)(same).toJava
  def cancel(): Unit = delegate.cancel()

}

/**
 * INTERNAL API
 *
 * Helper class to be able to express collection as a fold using mutable data
 */
@InternalApi private[akka] final class CollectorState[T, R](val collector: java.util.stream.Collector[T, Any, R]) {
  lazy val accumulated = collector.supplier().get()
  private lazy val accumulator = collector.accumulator()

  def update(elem: T): CollectorState[T, R] = {
    accumulator.accept(accumulated, elem)
    this
  }

  def finish(): R = collector.finisher().apply(accumulated)
}

/**
 * INTERNAL API
 *
 * Helper class to be able to express reduce as a fold for parallel collector
 */
@InternalApi private[akka] final class ReducerState[T, R](val collector: java.util.stream.Collector[T, Any, R]) {
  private var reduced: Any = null.asInstanceOf[Any]
  private lazy val combiner = collector.combiner()

  def update(batch: Any): ReducerState[T, R] = {
    if (reduced == null) reduced = batch
    else reduced = combiner(reduced, batch)
    this
  }

  def finish(): R = collector.finisher().apply(reduced)
}

/**
 * INTERNAL API
 */
@InternalApi final private[stream] class LazySink[T, M](sinkFactory: T ⇒ Future[Sink[T, M]], zeroMat: () ⇒ M) extends GraphStageWithMaterializedValue[SinkShape[T], Future[M]] {
  val in = Inlet[T]("lazySink.in")
  override def initialAttributes = DefaultAttributes.lazySink
  override val shape: SinkShape[T] = SinkShape.of(in)

  override def toString: String = "LazySink"

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes) = {
    lazy val decider = inheritedAttributes.mandatoryAttribute[SupervisionStrategy].decider

    var completed = false
    val promise = Promise[M]()
    val stageLogic = new GraphStageLogic(shape) with InHandler {
      override def preStart(): Unit = pull(in)

      override def onPush(): Unit = {
        try {
          val element = grab(in)
          val cb: AsyncCallback[Try[Sink[T, M]]] =
            getAsyncCallback {
              case Success(sink) ⇒ initInternalSource(sink, element)
              case Failure(e)    ⇒ failure(e)
            }
          sinkFactory(element).onComplete { cb.invoke }(ExecutionContexts.sameThreadExecutionContext)
          setHandler(in, new InHandler {
            override def onPush(): Unit = ()
            override def onUpstreamFinish(): Unit = gotCompletionEvent()
            override def onUpstreamFailure(ex: Throwable): Unit = failure(ex)
          })
        } catch {
          case NonFatal(e) ⇒ decider(e) match {
            case Supervision.Stop ⇒ failure(e)
            case _                ⇒ pull(in)
          }
        }
      }

      private def failure(ex: Throwable): Unit = {
        failStage(ex)
        promise.failure(ex)
      }

      override def onUpstreamFinish(): Unit = {
        completeStage()
        promise.tryComplete(Try(zeroMat()))
      }
      override def onUpstreamFailure(ex: Throwable): Unit = failure(ex)
      setHandler(in, this)

      private def gotCompletionEvent(): Unit = {
        setKeepGoing(true)
        completed = true
      }

      private def initInternalSource(sink: Sink[T, M], firstElement: T): Unit = {
        val sourceOut = new SubSourceOutlet[T]("LazySink")

        def switchToFirstElementHandlers(): Unit = {
          sourceOut.setHandler(new OutHandler {
            override def onPull(): Unit = {
              sourceOut.push(firstElement)
              if (completed) internalSourceComplete() else switchToFinalHandlers()
            }
            override def onDownstreamFinish(): Unit = internalSourceComplete()
          })

          setHandler(in, new InHandler {
            override def onPush(): Unit = sourceOut.push(grab(in))
            override def onUpstreamFinish(): Unit = gotCompletionEvent()
            override def onUpstreamFailure(ex: Throwable): Unit = internalSourceFailure(ex)
          })
        }

        def switchToFinalHandlers(): Unit = {
          sourceOut.setHandler(new OutHandler {
            override def onPull(): Unit = pull(in)
            override def onDownstreamFinish(): Unit = internalSourceComplete()
          })
          setHandler(in, new InHandler {
            override def onPush(): Unit = sourceOut.push(grab(in))
            override def onUpstreamFinish(): Unit = internalSourceComplete()
            override def onUpstreamFailure(ex: Throwable): Unit = internalSourceFailure(ex)
          })
        }

        def internalSourceComplete(): Unit = {
          sourceOut.complete()
          completeStage()
        }

        def internalSourceFailure(ex: Throwable): Unit = {
          sourceOut.fail(ex)
          failStage(ex)
        }

        switchToFirstElementHandlers()
        try {
          val matVal = Source.fromGraph(sourceOut.source).runWith(sink)(interpreter.subFusingMaterializer)
          promise.trySuccess(matVal)
        } catch {
          case NonFatal(ex) ⇒
            promise.tryFailure(ex)
            failStage(ex)
        }
      }

    }
    (stageLogic, promise.future)
  }
}
