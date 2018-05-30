package mesosphere.marathon
package core.storage.simple

import akka.stream.scaladsl.Flow
import akka.util.ByteString
import akka.{Done, NotUsed}
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.storage.simple.PersistenceStore._
import mesosphere.marathon.metrics.{Counter, Metrics, ServiceMetric}
import org.apache.zookeeper.KeeperException.NoNodeException

import scala.collection.JavaConverters
import scala.compat.java8.FutureConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Try}

/**
  * An implementation of the [[PersistenceStore]] interface.
  *
  * It utilises Apache Curator async methods under the hood from the org.apache.curator.curator-x-async library. This
  * is similar to synchronous methods with [[org.apache.curator.framework.api.Backgroundable]] callback however, it
  * uses a somewhat cleaner [asynchronous DSL](https://curator.apache.org/curator-x-async/index.html).
  *
  * Create, update, read and delete operation are offered as akka-stream Flows. Every Flow will offer an output signalising
  * that the input element was handled successfully. E.g. a [[create]] flow takes a stream of nodes to store and outputs
  * a stream of node paths indicating that the node was successfully stored.
  *
  * For CRUD operations the submitted stream elements are handled with configured parallelism. For create and update
  * operations the implementation guaranties that if multiple operations update the same path, the order of writes is the
  * same as in the input stream.
  *
  * Transaction support is implemented and four transaction operations are supported: [[CreateOp]], [[UpdateOp]],
  * [[DeleteOp]] and [[CheckOp]]. All transaction operations has to be successful for the transaction to commit successfully.
  *
  * @param factory an instance of [[AsyncCuratorBuilderFactory]]
  * @param parallelism parallelism level for CRUD operations
  * @param ec execution context
  */
class SimplePersistenceStore(factory: AsyncCuratorBuilderFactory, parallelism: Int = 10)(implicit ec: ExecutionContext)
  extends PersistenceStore with StrictLogging {

  // format: OFF
  private[this] val createMetric          = Metrics.counter(ServiceMetric, getClass, "create")
  private[this] val readMetric            = Metrics.counter(ServiceMetric, getClass, "read")
  private[this] val updatedMetric         = Metrics.counter(ServiceMetric, getClass, "update")
  private[this] val deleteMetric          = Metrics.counter(ServiceMetric, getClass, "delete")
  private[this] val childrenMetric        = Metrics.counter(ServiceMetric, getClass, "children")
  private[this] val existsMetric          = Metrics.counter(ServiceMetric, getClass, "exists")
  private[this] val transactionMetric     = Metrics.counter(ServiceMetric, getClass, "transaction")
  private[this] val transactionCountMetric = Metrics.counter(ServiceMetric, getClass, "transactionCount")


  // Helper stages for logging and metrics
  def logNode(prefix: String): Flow[Node, Node, NotUsed]                = Flow[Node].map{ n => logger.debug(s"$prefix${n.path}"); n }
  def logPath(prefix: String): Flow[String, String, NotUsed]            = Flow[String].map{ p => logger.debug(s"$prefix${p}"); p }
  def metric[T](counter: Counter, times: Long = 1): Flow[T, T, NotUsed] = Flow[T].map{ e => counter.increment(times); e}
  // format: ON

  override def create: Flow[Node, String, NotUsed] =
    Flow[Node]
      .via(logNode("Creating a node at "))
      .via(metric(createMetric))
      // `groupBy(path.hashCode % parallelism)` makes sure that updates to the same path always land in the same
      // sub-stream, thus keeping the order of writes to the same path, even with parallelism > 1
      .groupBy(parallelism, node => Math.abs(node.path.hashCode) % parallelism)
      .map(node => factory
        .create()
        .forPath(node.path, node.data.toArray)
        .toScala)
      .mapAsync(parallelism)(identity)
      .mergeSubstreams

  override def read: Flow[String, Try[Node], NotUsed] =
    Flow[String]
      .via(logPath("Reading a node at "))
      .via(metric(readMetric))
      .map { path =>
        factory
          .getData()
          .forPath(path)
          .toScala
          .map(bytes => Try(Node(path, ByteString(bytes))))
          .recover {
            case e: NoNodeException => Failure(e) // re-throw exception in all other cases
          }
      }
      .mapAsync(parallelism)(identity)

  override def update: Flow[Node, String, NotUsed] =
    Flow[Node]
      .via(logNode("Updating a node at "))
      .via(metric(updatedMetric))
      // `groupBy(path.hashCode % parallelism)` makes sure that updates to the same path always land in the same
      // sub-stream, thus keeping the order of writes to the same path, even with parallelism > 1
      .groupBy(parallelism, node => Math.abs(node.path.hashCode) % parallelism)
      .map(node => factory
        .setData()
        .forPath(node.path, node.data.toArray)
        .toScala
        .map(_ => node.path))
      .mapAsync(parallelism)(identity)
      .mergeSubstreams

  override def delete: Flow[String, String, NotUsed] =
    Flow[String]
      .via(logPath("Deleting a node at "))
      .via(metric(deleteMetric))
      .map(path => factory
        .delete()
        .forPath(path)
        .toScala
        .map(_ => path)) // Note: A Void is returned by the delete call and it can not be passed to the mapAsync stage
      .mapAsync(parallelism)(identity)

  override def children(path: String): Future[Seq[String]] = {
    childrenMetric.increment()
    logger.debug(s"Getting children at $path")
    factory
      .children()
      .forPath(path).toScala
      .map(JavaConverters.asScalaBuffer(_).to[Seq])
  }

  override def exists(path: String): Future[Boolean] = {
    existsMetric.increment()
    logger.debug(s"Checking node existence for $path")
    factory
      .checkExists()
      .forPath(path).toScala
      .map(stat => if (stat == null) false else true)
  }

  override def sync(path: String): Future[Done] = {
    logger.debug(s"Syncing nodes for path $path")
    factory
      .sync()
      .forPath(path).toScala
      .map(_ => Done)
  }

  override def transaction(operations: Seq[StoreOp]): Future[Done] = {
    logger.debug(s"Submitting a transaction with ${operations.size} operations")
    transactionCountMetric.increment(operations.size.toLong)
    transactionMetric.increment()

    val transactionOps = operations.map {
      case CreateOp(node) => factory.transactionOpCreate().forPath(node.path, node.data.toArray)
      case UpdateOp(node) => factory.transactionOpSetData().forPath(node.path, node.data.toArray)
      case DeleteOp(path) => factory.transactionOpDelete().forPath(path)
      case CheckOp(path) => factory.transactionOpCheck().forPath(path)
    }

    factory
      .transaction()
      .forOperations(JavaConverters.seqAsJavaList(transactionOps))
      .toScala
      .map(_ => Done)
  }
}