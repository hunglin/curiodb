/**
 * Aggregate commands - those that deal with multiple keys.
 */

package curiodb

import akka.actor.{Actor, ActorLogging, Props}
import akka.event.LoggingReceive
import com.dictiography.collections.IndexedTreeMap
import net.agkn.hll.HLL
import scala.collection.JavaConversions._
import scala.collection.mutable
import scala.math.{min, max}
import scala.util.{Success, Failure, Try}

/**
 * AggregateCommands is exclusively part of ClientNode, but defined
 * separately here for clarity. A ClientNode is responsible for
 * managing a single client connection, and handles certain commands
 * that don't go through the normal ClientNode -> KeyNode -> Node flow.
 * While some of these don't deal with Node actors at all, the bulk
 * are commands that must aggregate values from multiple Node actors,
 * which are all defined here. See the base Aggregate class for more
 * detail.
 */
trait AggregateCommands extends CommandProcessing {

  /**
   * CommandRunner for AggregateCommands, which is given a distinct
   * name, so that ClientNode can compose together multiple
   * CommandRunner methods to form its own.
   */
  def runAggregate: CommandRunner = {
    case "MSET"         => argsPaired.foreach {args => route(Seq("SET", args._1, args._2))}; SimpleReply()
    case "MSETNX"       => aggregate(Props[AggregateMSetNX])
    case "MGET"         => aggregate(Props[AggregateMGet])
    case "BITOP"        => aggregate(Props[AggregateBitOp])
    case "DBSIZE"       => aggregate(Props[AggregateDBSize])
    case "DEL"          => aggregate(Props[AggregateDel])
    case "KEYS"         => aggregate(Props[AggregateKeys])
    case "FLUSHDB"      => aggregate(Props[AggregateFlushDB])
    case "FLUSHALL"     => aggregate(Props[AggregateFlushAll])
    case "PFCOUNT"      => aggregate(Props[AggregateHyperLogLogCount])
    case "PFMERGE"      => aggregate(Props[AggregateHyperLogLogMerge])
    case "RANDOMKEY"    => aggregate(Props[AggregateRandomKey])
    case "SCAN"         => aggregate(Props[AggregateScan])
    case "SDIFF"        => aggregate(Props[AggregateSet])
    case "SINTER"       => aggregate(Props[AggregateSet])
    case "SUNION"       => aggregate(Props[AggregateSet])
    case "SDIFFSTORE"   => aggregate(Props[AggregateSetStore])
    case "SINTERSTORE"  => aggregate(Props[AggregateSetStore])
    case "SUNIONSTORE"  => aggregate(Props[AggregateSetStore])
    case "ZINTERSTORE"  => aggregate(Props[AggregateSortedSetStore])
    case "ZUNIONSTORE"  => aggregate(Props[AggregateSortedSetStore])
  }

}

/**
 * Aggregate is the base actor class for aggregate commands. An
 * aggregate command is one that requires data for multiple keys,
 * and therefore must retrieve data from multiple Node actors - this
 * means that normal ClientNode -> KeyNode -> Node flow for a command
 * does not suffice. Each of these command generally have a
 * corresponding Aggregate subclass.
 *
 * The flow of an aggregate command is one where a ClientNode creates
 * a temporary Aggregate actor that lives for the lifecycle of the
 * command being responded to - upon receiving a command, the Aggregate
 * actor breaks the command into the individual key/name/args
 * required per key, and sends these on the normal KeyNode -> Node
 * flow, with the Aggregate actor itself being the Command destination
 * for the response, rather than a CLientNode. The Aggregate actor
 * knows how many responses it requires (usually given by the number of
 * keys/nodes it deals with), and once all nodes have responded, it
 * then constructs the command's Response to send back to the
 * ClientNode.
 *
 * The construction of each Aggregate subclass takes a type parameter
 * specifying the response type it expects back from each Node, as well
 * as a command it will send to each Node it messages.
 *
 * Various aspects of the aggregation flow can be controlled by
 * overriding methods.
 */
abstract class Aggregate[T](val commandName: String) extends Actor with CommandProcessing with ActorLogging {

  /**
   * Typically the initial keys mapped to responses sent back from each
   * Node actor. The keys are used so that we can preserve the original
   * order of the keys when constructing the final response. In some
   * cases where keys are not applicable, an integer is used to maintain
   * order.
   */
  var responses = mutable.Map[String, T]()

  /**
   * Ordered set of keys dealt with by the aggregation, defaulting to
   * the original Command args of the command received.
   */
  def keys: Seq[String] = args

  /**
   * Returns responses ordered by their original key order.
   */
  def ordered: Seq[T] = keys.map(responses(_))

  /**
   * Constructs the final response to send back to the ClientNode.
   */
  def complete: Any = ordered

  /**
   * Starts the aggregation process by sending a Command containing
   * the Aggregate subclass instance's command, for each key in the
   * originating Command.
   */
  def begin = keys.foreach {key => route(Seq(commandName, key), destination = Some(self))}

  /**
   * Starts the aggregation process when the original Command is first
   * received, and receives each Node response afterwards, until all
   * responses have arrived, at which point the final response for the
   * ClientNode is constructed and sent back, and the Aggregate actor
   * is shut down.
   */
  def receive: Receive = LoggingReceive {
    case c: Command => command = c; begin
    case Response(key, value) =>
      val keyOrIndex = if (responses.contains(key)) (responses.size + 1).toString else key
      responses(keyOrIndex) = value.asInstanceOf[T]
      if (responses.size == keys.size) {
        respond(Try(complete) match {
          case Success(response) => response
          case Failure(e) => log.error(e, s"Error running: $command"); ErrorReply
        })
        stop
      }
  }

}

/**
 * Aggregate for the MGET command. Probably the simplest Aggregate as
 * it literally sends GET to each key, sending a list of responses
 * back to the ClientNode.
 */
class AggregateMGet extends Aggregate[String]("GET")

/**
 * Base Aggregate for all of the set operation commands, namely
 * SDIFF/SINTER/SUNION/SDIFFSTORE/SINTERSTORE/SUNIONSTORE. It
 * simply defines the set operation based on the command name, that
 * will be used in subclasses to reduce the results to a single set.
 */
abstract class AggregateSetReducer[T](commandName: String) extends Aggregate[T](commandName) {
  type S = mutable.Set[String]
  lazy val reducer: (S, S) => S = command.name.tail match {
    case x if x.startsWith("DIFF")  => (_ &~ _)
    case x if x.startsWith("INTER") => (_ & _)
    case x if x.startsWith("UNION") => (_ | _)
  }
}

/**
 * Base Aggregate for all of the non-storing set commands, namely
 * SDIFF/SINTER/SUNION. All it does is define the command used for
 * retrieving all members for each key, namely SMEMBERS.
 */
abstract class BaseAggregateSet extends AggregateSetReducer[mutable.Set[String]]("SMEMBERS")

/**
 * Aggregate for all of the non-storing set commands, namely
 * SDIFF/SINTER/SUNION. It glues together the reducing operation with
 * the completion process.
 */
class AggregateSet extends BaseAggregateSet {
  override def complete: Any = ordered.reduce(reducer)
}

/**
 * Aggregate for all of the storing set commands, namely
 * SDIFFSTORE/SINTERSTORE/SUNIONSTORE. It overrides the completion
 * process to store the reduced results in the appropriate Node,
 * and abort sending a response which will be handled by the final
 * Node being written to.
 */
class AggregateSetStore extends BaseAggregateSet {
  override def complete: Unit =
    route(Seq("_SSTORE", command.key) ++ ordered.reduce(reducer), destination = command.destination)
}

/**
 * Aggregate for all of the sorted set commands, namely
 * ZINTERSTORE/ZUNIONSTORE. It is very different from its
 * AggregateSetStore counterpart, given the AGGREGATE/WEIGHTS
 * args it supports.
 */
class AggregateSortedSetStore extends AggregateSetReducer[IndexedTreeMap[String, Int]]("_ZGET") {

  /**
   * Position of the AGGREGATE arg in the original Command.
   */
  lazy val aggregatePos = argsUpper.indexOf("AGGREGATE")

  /**
   * Value of the AGGREGATE arg in the original Command.
   */
  lazy val aggregateName = if (aggregatePos == -1) "SUM" else argsUpper(aggregatePos + 1)

  /**
   * Postition of the WEIGHT arg in the original Command.
   */
  lazy val weightPos = argsUpper.indexOf("WEIGHTS")

  /**
   * The actual operation that will be performed given the AGGREGATE
   * arg in the original Command.
   */
  lazy val aggregate: (Int, Int) => Int = aggregateName match {
    case "SUM" => (_ + _)
    case "MIN" => min _
    case "MAX" => max _
  }

  /**
   * Weight value to use, defaults to 1 if WEIGHT arg not defined.
   */
  def weight(i: Int): Int = if (weightPos == -1) 1 else args(weightPos + i + 1).toInt

  /**
   * Sorted Set aggregate commands define a required arg that
   * specifies the number of keys in the Command - here we use
   * that to pull out the keys.
   */
  override def keys: Seq[String] = args.slice(1, args(0).toInt + 1)

  /**
   * Reduces results based on the AGGREGATE/WEIGHT args in the original
   * Command, storing the reduced results in the appropriate Node,
   * and aborting sending a response which will be handled by the final
   * Node being written to.
   */
  override def complete: Unit = {
    var i = 0
    val result = ordered.reduce({(x, y) =>
      val out = new IndexedTreeMap[String, Int]()
      reducer(x.keySet, y.keySet).foreach {key =>
        lazy val xVal = x.get(key) * (if (i == 0) weight(i) else 1)
        lazy val yVal = y.get(key) * weight(i + 1)
        val value = if (!y.containsKey(key)) xVal
          else if (!x.containsKey(key)) yVal
          else aggregate(xVal, yVal)
        out.put(key, value)
      }
      i += 1
      out
    }).entrySet.toSeq.flatMap(e => Seq(e.getValue.toString, e.getKey))
    route(Seq("_ZSTORE", command.key) ++ result, destination = command.destination)
  }

}

/**
 * Aggregate for the BITOP command. Conceptually similar to
 * AggregateSetStore where the results are reduced then stored,
 * defering the final response to the Node being written to.
 */
class AggregateBitOp extends Aggregate[mutable.BitSet]("_BGET") {
  override def keys: Seq[String] = args.drop(2)
  override def complete: Unit = {
    val result = args(0).toUpperCase match {
      case "AND" => ordered.reduce(_ & _)
      case "OR"  => ordered.reduce(_ | _)
      case "XOR" => ordered.reduce(_ ^ _)
      case "NOT" =>
        val from = ordered(0).headOption.getOrElse(1) - 1
        val to = ordered(0).lastOption.getOrElse(1) - 1
        mutable.BitSet(from until to: _*) ^ ordered(0)
    }
    route(Seq("_BSTORE", args(1)) ++ result, destination = command.destination)
  }
}

/**
 * Aggregate for the PFCOUNT command. Simpy runs HLL count on the Node
 * for each key given, and sums the results.
 */
class AggregateHyperLogLogCount extends Aggregate[Long]("_PFCOUNT") {
  override def complete: Long = responses.values.sum
}

/**
 * Aggregate for the PFMERGE command. Reduces each HLL with a union
 * operation, storing the final result in the Node for the given key.
 */
class AggregateHyperLogLogMerge extends Aggregate[HLL]("_PFGET") {
  override def complete: Unit = {
    val result = ordered.reduce({(x, y) => x.union(y); x}).toBytes.map(_.toString)
    route(Seq("_PFSTORE", command.key) ++ result, destination = command.destination)
  }
}

/**
 * Base Aggregate for all commands that need to communicate with *all*
 * KeyNode actors, namely PUBSUB with the CHANNELS subcommand, and each
 * of the commands that deal with the keyspace itself, namely
 * KEYS/SCAN/DBSIZE/RANDOMKEY/DEL/EXISTS/FLUSHDB/FLUSHALL.
 *
 * As these commands don't deal with keys, define keys to means an
 * incremental integer, one for each KeyNode actor in the system.
 */
abstract class AggregateBroadcast[T](commandName: String) extends Aggregate[T](commandName) {

  /**
   * These are the args we'll use in the Command that is broadcast to
   * all KeyNode actors, since keys is overtaken in meaning.
   */
  def broadcastArgs: Seq[String] = command.args

  /**
   * For keys we use a range of integers, one for each KeyNode actor in
   * the system.
   */
  override def keys: Seq[String] = (1 to context.system.settings.config.getInt("curiodb.keynodes")).map(_.toString)

  /**
   * Constructs the broadcast Command for each KeyNode actor.
   */
  override def begin: Unit = route(commandName +: broadcastArgs, destination = Some(self), broadcast = true)

}

/**
 * Aggregate for the PUBSUB CHANNELS command/subcommand. Simply builds a
 * list of channels returned.
 */
class AggregatePubSubChannels extends AggregateBroadcast[Iterable[String]]("_CHANNELS") {
  override def broadcastArgs: Seq[String] = Seq(if (args.size == 2) args(1) else "*")
  override def complete: Iterable[String] = responses.values.reduce(_ ++ _)
}

/**
 * Aggregate for the PUBSUB NUMSUB command/subcommand. This is a normal
 * Aggregate subclass that simply returns a list of responses.
 */
class AggregatePubSubNumSub extends Aggregate[Int]("_NUMSUB") {

  /**
   * First arg is the NUMSUB subcommand, not a key.
   */
  override def keys: Seq[String] = args.drop(1)

  /**
   * Pair keys with responses.
   */
  override def complete: Seq[String] = keys.flatMap(x => Seq(x, responses(x).toString))

}

/**
 * Base Aggregate for all commands that need to read lists of keys
 * from each KeyNode actor, namely KEYS/SCAN/DBSIZE.
 */
abstract class BaseAggregateKeys extends AggregateBroadcast[Iterable[String]]("_KEYS") {
  def reduced: Iterable[String] = responses.values.reduce(_ ++ _)
}

/**
 * Aggregate for the KEYS command. Simply combines all keys returned
 */
class AggregateKeys extends BaseAggregateKeys {
  override def complete: Iterable[String] = reduced
}

/**
 * Aggregate for the SCAN command. Applies scan behavior in the same
 * way as the SSCAN/HSCAN/ZSCAN commands.
 */
class AggregateScan extends BaseAggregateKeys {

  /**
   * We need all keys returned so that we can locally apply the scan,
   * so here we specify the wildcard arg for each KeyNode actor's
   * Command.
   */
  override def broadcastArgs: Seq[String] = Seq("*")

  /**
   * Applies scan to the keys returned.
   */
  override def complete: Seq[String] = scan(reduced)

}

// TODO: add an internal command for getting size from keynode.
class AggregateDBSize extends BaseAggregateKeys {
  override def broadcastArgs: Seq[String] = Seq("*")
  override def complete: Int = reduced.size
}

/**
 * Aggregate for the RANDOMKEY command. To avoid pulling down all keys,
 * each KeyNode has an internal _RANDOMKEY command, and we then draw
 * a random one of these.
 */
class AggregateRandomKey extends AggregateBroadcast[String]("_RANDOMKEY") {
  override def complete: String = randomItem(responses.values.filter(_ != ""))
}

/**
 * Base Aggregate for commands that deal with boolean responses from
 * each KeyNode actor, namely DEL/EXISTS/MSETNX.
 */
abstract class BaseAggregateBool(commandName: String) extends AggregateBroadcast[Iterable[Boolean]](commandName) {

  /**
   * Returns all the true responses.
   */
  def trues: Iterable[Boolean] = responses.values.flatten.filter(_ == true)

}

/**
 * Aggregate for the DEL command. There's an unintuitive performance
 * consideration here - rather than sending a message per key, we
 * broadcast all keys to all KeyNode actors, and allow them to ignore
 * keys they don't manage. This means more data travelling through the
 * system, but limits the number of messages being sent, which greatly
 * improves performance for large sets of keys.
 */
class AggregateDel extends BaseAggregateBool("_DEL") {

  /**
   * Command args are actually keys, even though we're broadcasting.
   */
  override def broadcastArgs: Seq[String] = command.args

  /**
   * Each key that actually belong to a KeyNode will return true.
   */
  override def complete: Int = trues.size

}

/**
 * Aggregate for the MSETNX command. We first query the KeyNode actors
 * for each key existing, and only send values to store in Node actors
 * if none of the keys exist, as per the MSETNX command's behavior.
 */
class AggregateMSetNX extends BaseAggregateBool("EXISTS") {

  /**
   * Every odd arg is a key, and every even arg is a value.
   */
  override def keys: Seq[String] = command.argsPaired.map(_._1)

  override def complete: Boolean = {
    if (trues.isEmpty) command.argsPaired.foreach {args => route(Seq("SET", args._1, args._2))}
    trues.isEmpty
  }

}

/**
 * Base Aggregate for commands that don't need data for a reply, namely
 * FLUSHDB/FLUSHALL.
 */
abstract class AggregateSimpleReply(commandName: String) extends AggregateBroadcast[String](commandName) {
  override def complete: SimpleReply = SimpleReply()
}

/**
 * Aggregate for the FLUSHDB command. It simply sends off the
 * corresponding internal command to all KeyNode actors.
 */
class AggregateFlushDB extends AggregateSimpleReply("_FLUSHDB")

/**
 * Aggregate for the FLUSHALL command. It simply sends off the
 * corresponding internal command to all KeyNode actors.
 */
class AggregateFlushAll extends AggregateSimpleReply("_FLUSHALL")
