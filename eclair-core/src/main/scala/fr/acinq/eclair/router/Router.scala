/*
 * Copyright 2018 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.router

import java.util.zip.Adler32

import akka.actor.{ActorRef, Props, Status}
import akka.event.Logging.MDC
import akka.pattern.pipe
import fr.acinq.bitcoin.{BinaryData, Block}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{BinaryData, Satoshi}
import fr.acinq.eclair._
import fr.acinq.bitcoin.Script.{pay2wsh, write}
import fr.acinq.eclair.{router, _}
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.TransportHandler
import fr.acinq.eclair.io.Peer.{ChannelClosed, InvalidSignature, NonexistingChannel, PeerRoutingMessage}
import fr.acinq.eclair.payment.PaymentRequest.ExtraHop
import fr.acinq.eclair.router.Graph.GraphStructure.DirectedGraph.graphEdgeToHop
import fr.acinq.eclair.router.Graph.GraphStructure.{DirectedGraph, GraphEdge}
import fr.acinq.eclair.router.Graph.WeightedPath
import fr.acinq.eclair.transactions.Scripts
import fr.acinq.eclair.wire._
import shapeless.HNil

import scala.collection.{SortedSet, mutable}
import scala.collection.immutable.{SortedMap, TreeMap}
import scala.compat.Platform
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Promise}
import scala.util.{Random, Try}

// @formatter:off

case class ChannelDesc(shortChannelId: ShortChannelId, a: PublicKey, b: PublicKey)
case class Hop(nodeId: PublicKey, nextNodeId: PublicKey, lastUpdate: ChannelUpdate)
case class RouteRequest(source: PublicKey, target: PublicKey, amountMsat: Long, assistedRoutes: Seq[Seq[ExtraHop]] = Nil, ignoreNodes: Set[PublicKey] = Set.empty, ignoreChannels: Set[ChannelDesc] = Set.empty)
case class RouteResponse(hops: Seq[Hop], ignoreNodes: Set[PublicKey], ignoreChannels: Set[ChannelDesc]) {
  require(hops.size > 0, "route cannot be empty")
}
case class ExcludeChannel(desc: ChannelDesc) // this is used when we get a TemporaryChannelFailure, to give time for the channel to recover (note that exclusions are directed)
case class LiftChannelExclusion(desc: ChannelDesc)

// channel queries as specified in BOLT 1.0
case class SendChannelQuery(remoteNodeId: PublicKey, to: ActorRef)

// channel  queries with one extra timestamp, used by eclair prototypes
// remove ASAP i.e as soon as mobile apps have been updated with the new queries below
case class SendChannelQueryDeprecated(remoteNodeId: PublicKey, to: ActorRef)

// channel queries with 2 extra timestamps (one per chanel update) and a checksum, proposed for BOLT 1.1
case class SendChannelQueryWithChecksums(remoteNodeId: PublicKey, to: ActorRef)

case object GetRoutingState
case class RoutingState(channels: Iterable[ChannelAnnouncement], updates: Iterable[ChannelUpdate], nodes: Iterable[NodeAnnouncement])
case class Stash(updates: Map[ChannelUpdate, Set[ActorRef]], nodes: Map[NodeAnnouncement, Set[ActorRef]])
case class Rebroadcast(channels: Map[ChannelAnnouncement, Set[ActorRef]], updates: Map[ChannelUpdate, Set[ActorRef]], nodes: Map[NodeAnnouncement, Set[ActorRef]])

case class Sync(pending: List[RoutingMessage], total: Int)

case class Data(nodes: Map[PublicKey, NodeAnnouncement],
                channels: SortedMap[ShortChannelId, ChannelAnnouncement],
                updates: Map[ChannelDesc, ChannelUpdate],
                stash: Stash,
                awaiting: Map[ChannelAnnouncement, Seq[ActorRef]], // note: this is a seq because we want to preserve order: first actor is the one who we need to send a tcp-ack when validation is done
                privateChannels: Map[ShortChannelId, PublicKey], // short_channel_id -> node_id
                privateUpdates: Map[ChannelDesc, ChannelUpdate],
                excludedChannels: Set[ChannelDesc], // those channels are temporarily excluded from route calculation, because their node returned a TemporaryChannelFailure
                graph: DirectedGraph,
                sync: Map[PublicKey, Sync] // keep tracks of channel range queries sent to each peer. If there is an entry in the map, it means that there is an ongoing query
                                           // for which we have not yet received an 'end' message
               )

sealed trait State
case object NORMAL extends State

case object TickBroadcast
case object TickPruneStaleChannels

// @formatter:on

/**
  * Created by PM on 24/05/2016.
  */

class Router(nodeParams: NodeParams, watcher: ActorRef, initialized: Option[Promise[Unit]] = None) extends FSMDiagnosticActorLogging[State, Data] {

  import Router._

  import ExecutionContext.Implicits.global

  context.system.eventStream.subscribe(self, classOf[LocalChannelUpdate])
  context.system.eventStream.subscribe(self, classOf[LocalChannelDown])

  setTimer(TickBroadcast.toString, TickBroadcast, nodeParams.routerBroadcastInterval, repeat = true)
  setTimer(TickPruneStaleChannels.toString, TickPruneStaleChannels, 1 hour, repeat = true)

  val SHORTID_WINDOW = 100

  val db = nodeParams.networkDb

  {
    log.info("loading network announcements from db...")
    // On Android, we discard the node announcements
    val channels = db.listChannels()
    val updates = db.listChannelUpdates()
    log.info("loaded from db: channels={} nodes={} updates={}", channels.size, 0, updates.size)

    val initChannels = channels.keys.foldLeft(TreeMap.empty[ShortChannelId, ChannelAnnouncement]) { case (m, c) => m + (c.shortChannelId -> c) }
    val initChannelUpdates = updates.map { u =>
      val desc = getDesc(u, initChannels(u.shortChannelId))
      desc -> u
    }.toMap
    // this will be used to calculate routes
    val graph = DirectedGraph.makeGraph(initChannelUpdates)

    log.info(s"initialization completed, ready to process messages")
    Try(initialized.map(_.success(())))
    startWith(NORMAL, Data(Map.empty, initChannels, initChannelUpdates, Stash(Map.empty, Map.empty), awaiting = Map.empty, privateChannels = Map.empty, privateUpdates = Map.empty, excludedChannels = Set.empty, graph, sync = Map.empty))
  }

  when(NORMAL) {
    case Event(LocalChannelUpdate(_, _, shortChannelId, remoteNodeId, channelAnnouncement_opt, u, _), d: Data) =>
      d.channels.get(shortChannelId) match {
        case Some(_) =>
          // channel has already been announced and router knows about it, we can process the channel_update
          stay using handle(u, self, d)
        case None =>
          channelAnnouncement_opt match {
            case Some(c) if d.awaiting.contains(c) =>
              // channel is currently being verified, we can process the channel_update right away (it will be stashed)
              stay using handle(u, self, d)
            case Some(c) =>
              // channel wasn't announced but here is the announcement, we will process it *before* the channel_update
              watcher ! ValidateRequest(c)
              val d1 = d.copy(awaiting = d.awaiting + (c -> Nil)) // no origin
              // On android we don't track pruned channels in our db
              stay using handle(u, self, d1)
            case None if d.privateChannels.contains(shortChannelId) =>
              // channel isn't announced but we already know about it, we can process the channel_update
              stay using handle(u, self, d)
            case None =>
              // channel isn't announced and we never heard of it (maybe it is a private channel or maybe it is a public channel that doesn't yet have 6 confirmations)
              // let's create a corresponding private channel and process the channel_update
              log.info("adding unannounced local channel to remote={} shortChannelId={}", remoteNodeId, shortChannelId)
              stay using handle(u, self, d.copy(privateChannels = d.privateChannels + (shortChannelId -> remoteNodeId)))
          }
      }

    case Event(LocalChannelDown(_, channelId, shortChannelId, remoteNodeId), d: Data) =>
      // a local channel has permanently gone down
      if (d.channels.contains(shortChannelId)) {
        // the channel was public, we will receive (or have already received) a WatchEventSpentBasic event, that will trigger a clean up of the channel
        // so let's not do anything here
        stay
      } else if (d.privateChannels.contains(shortChannelId)) {
        // the channel was private or public-but-not-yet-announced, let's do the clean up
        log.debug("removing private local channel and channel_update for channelId={} shortChannelId={}", channelId, shortChannelId)
        val desc1 = ChannelDesc(shortChannelId, nodeParams.nodeId, remoteNodeId)
        val desc2 = ChannelDesc(shortChannelId, remoteNodeId, nodeParams.nodeId)
        // we remove the corresponding updates from the graph
        val graph1 = d.graph
          .removeEdge(desc1)
          .removeEdge(desc2)
        // and we remove the channel and channel_update from our state
        stay using d.copy(privateChannels = d.privateChannels - shortChannelId, privateUpdates = d.privateUpdates - desc1 - desc2, graph = graph1)
      } else {
        stay
      }

    case Event(GetRoutingState, d: Data) =>
      stay // ignored on Android

    case Event(WatchEventSpentBasic(BITCOIN_FUNDING_EXTERNAL_CHANNEL_SPENT(shortChannelId)), d) if d.channels.contains(shortChannelId) =>
      val lostChannel = d.channels(shortChannelId)
      log.info("funding tx of channelId={} has been spent", shortChannelId)
      // we need to remove nodes that aren't tied to any channels anymore
      val channels1 = d.channels - lostChannel.shortChannelId
      val lostNodes = Seq(lostChannel.nodeId1, lostChannel.nodeId2).filterNot(nodeId => hasChannels(nodeId, channels1.values))
      // let's clean the db and send the events
      log.info("pruning shortChannelId={} (spent)", shortChannelId)
      db.removeChannel(shortChannelId) // NB: this also removes channel updates
    // we also need to remove updates from the graph
    val graph1 = d.graph
      .removeEdge(ChannelDesc(lostChannel.shortChannelId, lostChannel.nodeId1, lostChannel.nodeId2))
      .removeEdge(ChannelDesc(lostChannel.shortChannelId, lostChannel.nodeId2, lostChannel.nodeId1))

      context.system.eventStream.publish(ChannelLost(shortChannelId))
      lostNodes.foreach {
        case nodeId =>
          log.info("pruning nodeId={} (spent)", nodeId)
          db.removeNode(nodeId)
          context.system.eventStream.publish(NodeLost(nodeId))
      }
      stay using d.copy(nodes = d.nodes -- lostNodes, channels = d.channels - shortChannelId, updates = d.updates.filterKeys(_.shortChannelId != shortChannelId), graph = graph1)

    case Event(TickBroadcast, d) =>
      // On Android we don't rebroadcast announcements
      stay

    case Event(TickPruneStaleChannels, d) =>
      // first we select channels that we will prune
      val staleChannels = getStaleChannels(d.channels.values, d.updates)
      // then we clean up the related channel updates
      val staleUpdates = staleChannels.map(d.channels).flatMap(c => Seq(ChannelDesc(c.shortChannelId, c.nodeId1, c.nodeId2), ChannelDesc(c.shortChannelId, c.nodeId2, c.nodeId1)))
      // finally we remove nodes that aren't tied to any channels anymore (and deduplicate them)
      val potentialStaleNodes = staleChannels.map(d.channels).flatMap(c => Set(c.nodeId1, c.nodeId2)).toSet
      val channels1 = d.channels -- staleChannels

      // let's clean the db and send the events
      staleChannels.foreach { shortChannelId =>
        log.info("pruning shortChannelId={} (stale)", shortChannelId)
        db.removeChannel(shortChannelId) // NB: this also removes channel updates
        // On Android we don't track pruned channels in our db
        context.system.eventStream.publish(ChannelLost(shortChannelId))
      }
      // we also need to remove updates from the graph
      val staleChannelsToRemove = new mutable.MutableList[ChannelDesc]
      staleChannels.map(d.channels).foreach(ca => {
        staleChannelsToRemove += ChannelDesc(ca.shortChannelId, ca.nodeId1, ca.nodeId2)
        staleChannelsToRemove += ChannelDesc(ca.shortChannelId, ca.nodeId2, ca.nodeId1)
      })

      val graph1 = d.graph.removeEdges(staleChannelsToRemove)
      stay using d.copy(channels = channels1, updates = d.updates -- staleUpdates, graph = graph1)

    case Event(ExcludeChannel(desc@ChannelDesc(shortChannelId, nodeId, _)), d) =>
      val banDuration = nodeParams.channelExcludeDuration
      log.info("excluding shortChannelId={} from nodeId={} for duration={}", shortChannelId, nodeId, banDuration)
      context.system.scheduler.scheduleOnce(banDuration, self, LiftChannelExclusion(desc))
      stay using d.copy(excludedChannels = d.excludedChannels + desc)

    case Event(LiftChannelExclusion(desc@ChannelDesc(shortChannelId, nodeId, _)), d) =>
      log.info("reinstating shortChannelId={} from nodeId={}", shortChannelId, nodeId)
      stay using d.copy(excludedChannels = d.excludedChannels - desc)

    case Event('nodes, d) =>
      sender ! d.nodes.values
      stay

    case Event('channels, d) =>
      sender ! d.channels.values
      stay

    case Event('updates, d) =>
      sender ! (d.updates ++ d.privateUpdates).values
      stay

    case Event('updatesMap, d) =>
      sender ! (d.updates ++ d.privateUpdates)
      stay

    case Event('data, d) =>
      sender ! d
      stay

    case Event(RouteRequest(start, end, amount, assistedRoutes, ignoreNodes, ignoreChannels), d) =>
      // we convert extra routing info provided in the payment request to fake channel_update
      // it takes precedence over all other channel_updates we know
      val assistedUpdates = assistedRoutes.flatMap(toFakeUpdates(_, end)).toMap
      // we also filter out updates corresponding to channels/nodes that are blacklisted for this particular request
      // TODO: in case of duplicates, d.updates will be overridden by assistedUpdates even if they are more recent!
      val ignoredUpdates = getIgnoredChannelDesc(d.updates ++ d.privateUpdates ++ assistedUpdates, ignoreNodes) ++ ignoreChannels ++ d.excludedChannels
      log.info(s"finding a route $start->$end with assistedChannels={} ignoreNodes={} ignoreChannels={} excludedChannels={}", assistedUpdates.keys.mkString(","), ignoreNodes.map(_.toBin).mkString(","), ignoreChannels.mkString(","), d.excludedChannels.mkString(","))
      val extraEdges = assistedUpdates.map { case (c, u) => GraphEdge(c, u) }.toSet
      // we ask the router to make a random selection among the three best routes, numRoutes = 3
      findRoute(d.graph, start, end, amount, numRoutes = DEFAULT_ROUTES_COUNT, extraEdges = extraEdges, ignoredEdges = ignoredUpdates.toSet)
        .map(r => sender ! RouteResponse(r, ignoreNodes, ignoreChannels))
        .recover { case t => sender ! Status.Failure(t) }
      stay

    case Event(SendChannelQuery(remoteNodeId, remote), d) =>
      // ask for everything
      // we currently send only one query_channel_range message per peer, when we just (re)connected to it, so we don't
      // have to worry about sending a new query_channel_range when another query is still in progress
      val query = QueryChannelRange(nodeParams.chainHash, firstBlockNum = 0, numberOfBlocks = Int.MaxValue)
      log.info("sending query_channel_range={}", query)
      remote ! query

      // we also set a pass-all filter for now (we can update it later)
      val filter = GossipTimestampFilter(nodeParams.chainHash, firstTimestamp = 0, timestampRange = Int.MaxValue)
      remote ! filter

      // clean our sync state for this peer: we receive a SendChannelQuery just when we connect/reconnect to a peer and
      // will start a new complete sync process
      stay using d.copy(sync = d.sync - remoteNodeId)

    case Event(SendChannelQueryDeprecated(remoteNodeId, remote), d) =>
      // ask for everything
      val query = QueryChannelRangeDeprecated(nodeParams.chainHash, firstBlockNum = 0, numberOfBlocks = Int.MaxValue)
      log.info("sending query_channel_range_proto={}", query)
      remote ! query
      // we also set a pass-all filter for now (we can update it later)
      val filter = GossipTimestampFilter(nodeParams.chainHash, firstTimestamp = 0, timestampRange = Int.MaxValue)
      remote ! filter
      // clean our sync state for this peer: we receive a SendChannelQuery just when we connect/reconnect to a peer and
      // will start a new complete sync process
      stay using d.copy(sync = d.sync - remoteNodeId)

    case Event(SendChannelQueryWithChecksums(remoteNodeId, remote), d) =>
      // ask for everything
      val query = QueryChannelRangeWithChecksums(nodeParams.chainHash, firstBlockNum = 0, numberOfBlocks = Int.MaxValue)
      log.info("sending query_channel_range_with_checksums={}", query)
      remote ! query
      // we also set a pass-all filter for now (we can update it later)
      val filter = GossipTimestampFilter(nodeParams.chainHash, firstTimestamp = 0, timestampRange = Int.MaxValue)
      remote ! filter
      // clean our sync state for this peer: we receive a SendChannelQuery just when we connect/reconnect to a peer and
      // will start a new complete sync process
      stay using d.copy(sync = d.sync - remoteNodeId)

    // Warning: order matters here, this must be the first match for HasChainHash messages !
    case Event(PeerRoutingMessage(_, _, routingMessage: HasChainHash), d) if routingMessage.chainHash != nodeParams.chainHash =>
      sender ! TransportHandler.ReadAck(routingMessage)
      log.warning("message {} for wrong chain {}, we're on {}", routingMessage, routingMessage.chainHash, nodeParams.chainHash)
      stay

    case Event(u: ChannelUpdate, d: Data) =>
      // it was sent by us, routing messages that are sent by  our peers are now wrapped in a PeerRoutingMessage
      log.debug("received channel update from {}", sender)
      stay using handle(u, sender, d)

    case Event(PeerRoutingMessage(transport, remoteNodeId, u: ChannelUpdate), d) =>
      sender ! TransportHandler.ReadAck(u)
      log.debug("received channel update for shortChannelId={}", u.shortChannelId)
      stay using handle(u, sender, d, remoteNodeId_opt = Some(remoteNodeId), transport_opt = Some(transport))

    case Event(PeerRoutingMessage(_, _, c: ChannelAnnouncement), d) =>
      log.debug("received channel announcement for shortChannelId={} nodeId1={} nodeId2={}", c.shortChannelId, c.nodeId1, c.nodeId2)
      if (d.channels.contains(c.shortChannelId)) {
        sender ! TransportHandler.ReadAck(c)
        log.debug("ignoring {} (duplicate)", c)
        stay
      } else if (d.awaiting.contains(c)) {
        sender ! TransportHandler.ReadAck(c)
        log.debug("ignoring {} (being verified)", c)
        // adding the sender to the list of origins so that we don't send back the same announcement to this peer later
        val origins = d.awaiting(c) :+ sender
        stay using d.copy(awaiting = d.awaiting + (c -> origins))
      } else if (!Announcements.checkSigs(c)) {
        // On Android we don't track pruned channels in our db
        sender ! TransportHandler.ReadAck(c)
        log.warning("bad signature for announcement {}", c)
        sender ! InvalidSignature(c)
        stay
      } else {
        // On Android, after checking the sig we remove as much data as possible to reduce RAM consumption
        val c1 = c.copy(
          nodeSignature1 = null,
          nodeSignature2 = null,
          bitcoinSignature1 = null,
          bitcoinSignature2 = null,
          features = null,
          chainHash = null,
          bitcoinKey1 = null,
          bitcoinKey2 = null)
        sender ! TransportHandler.ReadAck(c)
        // On Android, we don't validate announcements for now, it means that neither awaiting nor stashed announcements are used
        db.addChannel(c1, BinaryData(""), Satoshi(0))
        stay using d.copy(
          channels = d.channels + (c1.shortChannelId -> c1),
          privateChannels = d.privateChannels - c1.shortChannelId // we remove fake announcements that we may have made before)
        )
      }

    case Event(n: NodeAnnouncement, d: Data) =>
      // it was sent by us, routing messages that are sent by  our peers are now wrapped in a PeerRoutingMessage
      stay // we just ignore node_announcements on Android

    case Event(PeerRoutingMessage(_, _, n: NodeAnnouncement), d: Data) =>
      sender ! TransportHandler.ReadAck(n)
      stay // we just ignore node_announcements on Android

    case Event(PeerRoutingMessage(transport, _, routingMessage@QueryChannelRange(chainHash, firstBlockNum, numberOfBlocks)), d) =>
      sender ! TransportHandler.ReadAck(routingMessage)
      // On Android we ignore queries
      stay

    case Event(PeerRoutingMessage(transport, _, routingMessage@QueryChannelRangeDeprecated(chainHash, firstBlockNum, numberOfBlocks)), d) =>
      sender ! TransportHandler.ReadAck(routingMessage)
      // On Android we ignore queries
      stay

    case Event(PeerRoutingMessage(transport, _, routingMessage@QueryChannelRangeWithChecksums(chainHash, firstBlockNum, numberOfBlocks)), d) =>
      sender ! TransportHandler.ReadAck(routingMessage)
      // On Android we ignore queries
      stay

    case Event(PeerRoutingMessage(transport, remoteNodeId, routingMessage@ReplyChannelRange(chainHash, firstBlockNum, numberOfBlocks, _, data)), d) =>
      sender ! TransportHandler.ReadAck(routingMessage)
      val theirShortChannelIds: SortedSet[ShortChannelId] = SortedSet(data.array: _*)
      val ourShortChannelIds: SortedSet[ShortChannelId] = d.channels.keySet.filter(keep(firstBlockNum, numberOfBlocks, _))
      val missing: SortedSet[ShortChannelId] = theirShortChannelIds -- ourShortChannelIds
      log.info("received reply_channel_range, we're missing {} channel announcements/updates, format={}", missing.size, data.encoding)
      // we update our sync data to this node (there may be multiple channel range responses and we can only query one set of ids at a time)
      val replies = missing
        .grouped(SHORTID_WINDOW)
        .map(chunk => QueryShortChannelIds(chainHash, data = EncodedShortChannelIds(data.encoding, chunk.toList)))
        .toList
      val (sync1, replynow_opt) = updateSync(d.sync, remoteNodeId, replies)
      // we only send a rely right away if there were no pending requests
      replynow_opt.foreach(transport ! _)
      context.system.eventStream.publish(syncProgress(sync1))
      stay using d.copy(sync = sync1)

    case Event(PeerRoutingMessage(transport, remoteNodeId, routingMessage@ReplyChannelRangeDeprecated(chainHash, firstBlockNum, numberOfBlocks, _, data)), d) =>
      sender ! TransportHandler.ReadAck(routingMessage)
      val missing = data.array
        .filter { channelInfo =>
          // we request unknown channels
          !d.channels.contains(channelInfo.shortChannelId) ||
            // and known channels for which our timestamp is older than theirs
            Router.getTimestamp(d.channels, d.updates)(channelInfo.shortChannelId) < channelInfo.timestamp
        }
        .map(_.shortChannelId)
      log.info("received reply_channel_range_deprecated, we're missing {} channel announcements/updates, format={}", missing.size, data.encoding)
      // TODO: simplification! we always request all data (this is deprecated anyway)
      val flag = (FlagTypes.INCLUDE_ANNOUNCEMENT | FlagTypes.INCLUDE_CHANNEL_UPDATE_1 | FlagTypes.INCLUDE_CHANNEL_UPDATE_2).toByte
      // we update our sync data to this node (there may be multiple channel range responses and we can only query one set of ids at a time)
      val replies = missing
        .grouped(SHORTID_WINDOW)
        .map(chunk => QueryShortChannelIdsDeprecated(chainHash, flag, data = EncodedShortChannelIds(data.encoding, chunk)))
        .toList
      val (sync1, replynow_opt) = updateSync(d.sync, remoteNodeId, replies)
      // we only send a rely right away if there were no pending requests
      replynow_opt.foreach(transport ! _)
      context.system.eventStream.publish(syncProgress(sync1))

      // we have channel announcement that they don't have: check if we can prune them
      val pruningCandidates = {
        val first = ShortChannelId(firstBlockNum.toInt, 0, 0)
        val last = ShortChannelId((firstBlockNum + numberOfBlocks).toInt, 0xFFFFFFFF, 0xFFFF)
        // channel ids are sorted so we can simplify our range check
        val shortChannelIds = d.channels.keySet.dropWhile(_ < first).takeWhile(_ <= last) -- data.array.map(_.shortChannelId).toSet
        log.info("we have {} channel that they do not have", shortChannelIds.size)
        d.channels.filterKeys(id => shortChannelIds.contains(id))
      }

      // we limit the maximum number of channels that we will prune in one go to avoid "freezing" the app
      // we first check which candidates are stale, then cap the result. We could also cap the candidate list first, there
      // would be less calls to getStaleChannels but it would be less efficient from a "pruning" p.o.v
      val staleChannels = getStaleChannels(pruningCandidates.values, d.updates).take(MAX_PRUNE_COUNT)
      // then we clean up the related channel updates
      val staleUpdates = staleChannels.map(d.channels).flatMap(c => Seq(ChannelDesc(c.shortChannelId, c.nodeId1, c.nodeId2), ChannelDesc(c.shortChannelId, c.nodeId2, c.nodeId1)))
      val channels1 = d.channels -- staleChannels

      // let's clean the db and send the events
      staleChannels.foreach { shortChannelId =>
        log.info("pruning shortChannelId={} (stale)", shortChannelId)
        db.removeChannel(shortChannelId) // NB: this also removes channel updates
        context.system.eventStream.publish(ChannelLost(shortChannelId))
      }
      // we also need to remove updates from the graph
      val staleChannelsToRemove = new mutable.MutableList[ChannelDesc]
      staleChannels.map(d.channels).foreach( ca => {
        staleChannelsToRemove += ChannelDesc(ca.shortChannelId, ca.nodeId1, ca.nodeId2)
        staleChannelsToRemove += ChannelDesc(ca.shortChannelId, ca.nodeId2, ca.nodeId1)
      })
      val graph1 = d.graph.removeEdges(staleChannelsToRemove)

      stay using d.copy(channels = channels1, updates = d.updates -- staleUpdates, graph = graph1, sync = sync1)

    case Event(PeerRoutingMessage(transport, remoteNodeId, routingMessage@ReplyChannelRangeWithChecksums(chainHash, firstBlockNum, numberOfBlocks, _, data)), d) =>
      sender ! TransportHandler.ReadAck(routingMessage)
      val shortChannelIdAndFlags = data.array
        .map { theirInfo =>
          var flag = 0
          if (d.channels.contains(theirInfo.shortChannelId)) {
            val ourInfo = Router.getChannelDigestInfo(d.channels, d.updates)(theirInfo.shortChannelId)
            // we request their channel_update if all those conditions are met:
            // - it is more recent than ours
            // - it is different from ours, or it is the same but ours is about to be stale
            // - it is not stale itself
            if (ourInfo.timestamp1 < theirInfo.timestamp1 && (ourInfo.checksum1 != theirInfo.checksum1 || isAlmostStale(ourInfo.timestamp1)) && !isStale(theirInfo.timestamp1)) flag = flag | FlagTypes.INCLUDE_CHANNEL_UPDATE_1
            if (ourInfo.timestamp2 < theirInfo.timestamp2 && (ourInfo.checksum2 != theirInfo.checksum2 || isAlmostStale(ourInfo.timestamp1)) && !isStale(theirInfo.timestamp2)) flag = flag | FlagTypes.INCLUDE_CHANNEL_UPDATE_2
          } else {
            // we don't know this channel: we request everything
            flag = flag | FlagTypes.INCLUDE_ANNOUNCEMENT | FlagTypes.INCLUDE_CHANNEL_UPDATE_1 | FlagTypes.INCLUDE_CHANNEL_UPDATE_2
          }
          ShortChannelIdAndFlag(theirInfo.shortChannelId, flag.toByte)
        }
        .filter(_.flag != 0)
      val (channelCount, updatesCount) = shortChannelIdAndFlags.foldLeft((0, 0)) {
        case ((c, u), ShortChannelIdAndFlag(_, flag)) =>
          val c1 = c + (if (FlagTypes.includeAnnouncement(flag)) 1 else 0)
          val u1 = u + (if (FlagTypes.includeUpdate1(flag)) 1 else 0) + (if (FlagTypes.includeUpdate2(flag)) 1 else 0)
          (c1, u1)
      }
      log.info("received reply_channel_range_with_checksums with {} channels, we're missing {} channel announcements and {} updates, format={}", data.array.size, channelCount, updatesCount, data.encoding)
      // we update our sync data to this node (there may be multiple channel range responses and we can only query one set of ids at a time)
      val replies = shortChannelIdAndFlags
        .grouped(SHORTID_WINDOW)
        .map(chunk => QueryShortChannelIdsWithFlags(chainHash, data = EncodedShortChannelIdsAndFlag(data.encoding, chunk)))
        .toList
      val (sync1, replynow_opt) = updateSync(d.sync, remoteNodeId, replies)
      // we only send a rely right away if there were no pending requests
      replynow_opt.foreach(transport ! _)
      context.system.eventStream.publish(syncProgress(sync1))

      // we have channel announcement that they don't have: check if we can prune them
      val pruningCandidates = {
        val first = ShortChannelId(firstBlockNum.toInt, 0, 0)
        val last = ShortChannelId((firstBlockNum + numberOfBlocks).toInt, 0xFFFFFFFF, 0xFFFF)
        // channel ids are sorted so we can simplify our range check
        val shortChannelIds = d.channels.keySet.dropWhile(_ < first).takeWhile(_ <= last) -- data.array.map(_.shortChannelId).toSet
        log.info("we have {} channels that they do not have between block {} and block {}", shortChannelIds.size, ShortChannelId.coordinates(first).blockHeight, ShortChannelId.coordinates(last).blockHeight)
        d.channels.filterKeys(id => shortChannelIds.contains(id))
      }

      // we limit the maximum number of channels that we will prune in one go to avoid "freezing" the app
      // we first check which candidates are stale, then cap the result. We could also cap the candidate list first, there
      // would be less calls to getStaleChannels but it would be less efficient from a "pruning" p.o.v
      val staleChannels = getStaleChannels(pruningCandidates.values, d.updates).take(MAX_PRUNE_COUNT)
      // then we clean up the related channel updates
      val staleUpdates = staleChannels.map(d.channels).flatMap(c => Seq(ChannelDesc(c.shortChannelId, c.nodeId1, c.nodeId2), ChannelDesc(c.shortChannelId, c.nodeId2, c.nodeId1)))
      val channels1 = d.channels -- staleChannels

      // let's clean the db and send the events
      staleChannels.foreach { shortChannelId =>
        log.info("pruning shortChannelId={} (stale)", shortChannelId)
        db.removeChannel(shortChannelId) // NB: this also removes channel updates
        context.system.eventStream.publish(ChannelLost(shortChannelId))
      }
      // we also need to remove updates from the graph
      val staleChannelsToRemove = new mutable.MutableList[ChannelDesc]
      staleChannels.map(d.channels).foreach( ca => {
        staleChannelsToRemove += ChannelDesc(ca.shortChannelId, ca.nodeId1, ca.nodeId2)
        staleChannelsToRemove += ChannelDesc(ca.shortChannelId, ca.nodeId2, ca.nodeId1)
      })
      val graph1 = d.graph.removeEdges(staleChannelsToRemove)

      stay using d.copy(channels = channels1, updates = d.updates -- staleUpdates, graph = graph1, sync = sync1)

    // standard query message: a list of channel ids
    case Event(PeerRoutingMessage(transport, _, routingMessage@QueryShortChannelIds(chainHash, data)), d) =>
      sender ! TransportHandler.ReadAck(routingMessage)
      // On Android we ignore queries
      stay

    // extended query message: a flag and a list of channel ids
    case Event(PeerRoutingMessage(transport, _, routingMessage@QueryShortChannelIdsDeprecated(chainHash, flag, data)), d) =>
      sender ! TransportHandler.ReadAck(routingMessage)
      // On Android we ignore queries
      stay

    // new extended query message: a list of [channel id + flag]
    case Event(PeerRoutingMessage(transport, _, routingMessage@QueryShortChannelIdsWithFlags(chainHash, data)), d) =>
      sender ! TransportHandler.ReadAck(routingMessage)
      // On Android we ignore queries
      stay

    case Event(PeerRoutingMessage(transport, remoteNodeId, routingMessage: ReplyShortChannelIdsEnd), d) =>
      sender ! TransportHandler.ReadAck(routingMessage)
      stay using handleSyncEnd(d, remoteNodeId, transport)

    case Event(PeerRoutingMessage(transport, remoteNodeId, routingMessage: ReplyShortChannelIdsEndDeprecated), d) =>
      sender ! TransportHandler.ReadAck(routingMessage)
      stay using handleSyncEnd(d, remoteNodeId, transport)

    case Event(PeerRoutingMessage(transport, remoteNodeId, routingMessage: ReplyShortChannelIdsWithFlagsEnd), d) =>
      sender ! TransportHandler.ReadAck(routingMessage)
      stay using handleSyncEnd(d, remoteNodeId, transport)

  }

  initialize()

  def handle(n: NodeAnnouncement, origin: ActorRef, d: Data): Data =
    if (d.stash.nodes.contains(n)) {
      log.debug("ignoring {} (already stashed)", n)
      val origins = d.stash.nodes(n) + origin
      d.copy(stash = d.stash.copy(nodes = d.stash.nodes + (n -> origins)))
    } else if (d.nodes.contains(n.nodeId) && d.nodes(n.nodeId).timestamp >= n.timestamp) {
      log.debug("ignoring {} (duplicate)", n)
      d
    } else if (!Announcements.checkSig(n)) {
      log.warning("bad signature for {}", n)
      origin ! InvalidSignature(n)
      d
    } else if (d.nodes.contains(n.nodeId)) {
      log.debug("updated node nodeId={}", n.nodeId)
      context.system.eventStream.publish(NodeUpdated(n))
      db.updateNode(n)
      d.copy(nodes = d.nodes + (n.nodeId -> n))
    } else if (d.channels.values.exists(c => isRelatedTo(c, n.nodeId))) {
      log.debug("added node nodeId={}", n.nodeId)
      context.system.eventStream.publish(NodeDiscovered(n))
      db.addNode(n)
      d.copy(nodes = d.nodes + (n.nodeId -> n))
    } else if (d.awaiting.keys.exists(c => isRelatedTo(c, n.nodeId))) {
      log.debug("stashing {}", n)
      d.copy(stash = d.stash.copy(nodes = d.stash.nodes + (n -> Set(origin))))
    } else {
      log.debug("ignoring {} (no related channel found)", n)
      // there may be a record if we have just restarted
      db.removeNode(n.nodeId)
      d
    }

  def handle(u: ChannelUpdate, origin: ActorRef, d: Data, remoteNodeId_opt: Option[PublicKey] = None, transport_opt: Option[ActorRef] = None): Data = {
    // On Android, after checking the sig we remove as much data as possible to reduce RAM consumption
    val u1 = u.copy(
      signature = null,
      chainHash = null
    )
    if (d.channels.contains(u.shortChannelId)) {
      // related channel is already known (note: this means no related channel_update is in the stash)
      val publicChannel = true
      val c = d.channels(u.shortChannelId)
      val desc = getDesc(u, c)
      if (isStale(u)) {
        log.debug("ignoring {} (stale)", u)
        d
      } else if (d.updates.contains(desc) && d.updates(desc).timestamp >= u.timestamp) {
        log.debug("ignoring {} (duplicate)", u)
        d
      } else if (!Announcements.checkSig(u, desc.a)) {
        log.warning("bad signature for announcement shortChannelId={} {}", u.shortChannelId, u)
        origin ! InvalidSignature(u)
        d
      } else if (d.updates.contains(desc)) {
        log.debug("updated channel_update for shortChannelId={} public={} flags={} {}", u.shortChannelId, publicChannel, u.channelFlags, u)
        context.system.eventStream.publish(ChannelUpdateReceived(u))
        db.updateChannelUpdate(u1)
        // we also need to update the graph
        val graph1 = Announcements.isEnabled(u1.channelFlags) match {
          case true => d.graph.removeEdge(desc).addEdge(desc, u1)
          case false => d.graph.removeEdge(desc) // if the channel is now disabled, we remove it from the graph
        }
        d.copy(updates = d.updates + (desc -> u1), graph = graph1)
      } else {
        log.debug("added channel_update for shortChannelId={} public={} flags={} {}", u.shortChannelId, publicChannel, u.channelFlags, u)
        context.system.eventStream.publish(ChannelUpdateReceived(u))
        db.addChannelUpdate(u1)
        // we also need to update the graph
        val graph1 = d.graph.addEdge(desc, u1)
        d.copy(updates = d.updates + (desc -> u1), privateUpdates = d.privateUpdates - desc, graph = graph1)
      }
    } else if (d.awaiting.keys.exists(c => c.shortChannelId == u.shortChannelId)) {
      // channel is currently being validated
      if (d.stash.updates.contains(u)) {
        log.debug("ignoring {} (already stashed)", u)
        val origins = d.stash.updates(u) + origin
        d.copy(stash = d.stash.copy(updates = d.stash.updates + (u -> origins)))
      } else {
        log.debug("stashing {}", u)
        d.copy(stash = d.stash.copy(updates = d.stash.updates + (u -> Set(origin))))
      }
    } else if (d.privateChannels.contains(u.shortChannelId)) {
      val publicChannel = false
      val remoteNodeId = d.privateChannels(u.shortChannelId)
      val (a, b) = if (Announcements.isNode1(nodeParams.nodeId, remoteNodeId)) (nodeParams.nodeId, remoteNodeId) else (remoteNodeId, nodeParams.nodeId)
      val desc = if (Announcements.isNode1(u.channelFlags)) ChannelDesc(u.shortChannelId, a, b) else ChannelDesc(u.shortChannelId, b, a)
      if (isStale(u)) {
        log.debug("ignoring {} (stale)", u)
        d
      } else if (d.updates.contains(desc) && d.updates(desc).timestamp >= u.timestamp) {
        log.debug("ignoring {} (already know same or newer)", u)
        d
      } else if (!Announcements.checkSig(u, desc.a)) {
        log.warning("bad signature for announcement shortChannelId={} {}", u.shortChannelId, u)
        origin ! InvalidSignature(u)
        d
      } else if (d.privateUpdates.contains(desc)) {
        log.debug("updated channel_update for shortChannelId={} public={} flags={} {}", u.shortChannelId, publicChannel, u.channelFlags, u)
        context.system.eventStream.publish(ChannelUpdateReceived(u))
        // we also need to update the graph
        val graph1 = d.graph.removeEdge(desc).addEdge(desc, u1)
        d.copy(privateUpdates = d.privateUpdates + (desc -> u1), graph = graph1)
      } else {
        log.debug("added channel_update for shortChannelId={} public={} flags={} {}", u.shortChannelId, publicChannel, u.channelFlags, u)
        context.system.eventStream.publish(ChannelUpdateReceived(u))
        // we also need to update the graph
        val graph1 = d.graph.addEdge(desc, u1)
        d.copy(privateUpdates = d.privateUpdates + (desc -> u1), graph = graph1)
       }
    } else {
      // On android we don't track pruned channels in our db
      log.debug("ignoring announcement {} (unknown channel)", u)
      d
    }
  }

  def handleSyncEnd(d: Data, remoteNodeId: PublicKey, transport: ActorRef): Data = {
    // have we more channels to ask this peer?
    val sync1 = d.sync.get(remoteNodeId) match {
      case Some(sync) =>
        sync.pending match {
          case nextRequest +: rest =>
            log.info(s"asking for the next slice of short_channel_ids (remaining=${sync.pending.size}/${sync.total})")
            transport ! nextRequest
            d.sync + (remoteNodeId -> sync.copy(pending = rest))
          case Nil =>
            // we received reply_short_channel_ids_end for our last query and have not sent another one, we can now remove
            // the remote peer from our map
            log.info(s"sync complete (total=${sync.total})")
            d.sync - remoteNodeId
        }
      case _ => d.sync
    }
    context.system.eventStream.publish(syncProgress(sync1))
    d.copy(sync = sync1)
  }

  def handleChannelQuery[T](d: Data, transport: ActorRef, items: Iterable[T], id: T => ShortChannelId, sendChannel: T => Boolean, sendUpdate1: T => Boolean, sendUpdate2: T => Boolean): (Int, Int) = {
    items.foldLeft((0, 0)) {
      case ((c, u), item) =>
        var c1 = c
        var u1 = u
        val shortChannelId = id(item)
        d.channels.get(shortChannelId) match {
          case None => log.warning("received query for shortChannelId={} that we don't have", shortChannelId)
          case Some(ca) =>
            if (sendChannel(item)) {
              transport ! ca
              c1 = c1 + 1
            }
            if (sendUpdate1(item)) d.updates.get(ChannelDesc(ca.shortChannelId, ca.nodeId1, ca.nodeId2)).foreach { u => transport ! u; u1 = u1 + 1 }
            if (sendUpdate2(item)) d.updates.get(ChannelDesc(ca.shortChannelId, ca.nodeId2, ca.nodeId1)).foreach { u => transport ! u; u1 = u1 + 1 }
        }
        (c1, u1)
    }
  }

  override def mdc(currentMessage: Any): MDC = currentMessage match {
    case SendChannelQuery(remoteNodeId, _) => Logs.mdc(remoteNodeId_opt = Some(remoteNodeId))
    case PeerRoutingMessage(_, remoteNodeId, _) => Logs.mdc(remoteNodeId_opt = Some(remoteNodeId))
    case _ => akka.event.Logging.emptyMDC
  }
}

object Router {
  val SHORTID_WINDOW = 100

  def props(nodeParams: NodeParams, watcher: ActorRef, initialized: Option[Promise[Unit]] = None) = Props(new Router(nodeParams, watcher, initialized))

  def toFakeUpdate(extraHop: ExtraHop): ChannelUpdate =
  // the `direction` bit in flags will not be accurate but it doesn't matter because it is not used
  // what matters is that the `disable` bit is 0 so that this update doesn't get filtered out
    ChannelUpdate(signature = "", chainHash = "", extraHop.shortChannelId, Platform.currentTime / 1000, messageFlags = 0, channelFlags = 0, extraHop.cltvExpiryDelta, htlcMinimumMsat = 0L, extraHop.feeBaseMsat, extraHop.feeProportionalMillionths, None)

  def toFakeUpdates(extraRoute: Seq[ExtraHop], targetNodeId: PublicKey): Map[ChannelDesc, ChannelUpdate] = {
    // BOLT 11: "For each entry, the pubkey is the node ID of the start of the channel", and the last node is the destination
    val nextNodeIds = extraRoute.map(_.nodeId).drop(1) :+ targetNodeId
    extraRoute.zip(nextNodeIds).map {
      case (extraHop: ExtraHop, nextNodeId) => (ChannelDesc(extraHop.shortChannelId, extraHop.nodeId, nextNodeId) -> toFakeUpdate(extraHop))
    }.toMap
  }

  def getDesc(u: ChannelUpdate, channel: ChannelAnnouncement): ChannelDesc = {
    // the least significant bit tells us if it is node1 or node2
    if (Announcements.isNode1(u.channelFlags)) ChannelDesc(u.shortChannelId, channel.nodeId1, channel.nodeId2) else ChannelDesc(u.shortChannelId, channel.nodeId2, channel.nodeId1)
  }

  def isRelatedTo(c: ChannelAnnouncement, nodeId: PublicKey) = nodeId == c.nodeId1 || nodeId == c.nodeId2

  def hasChannels(nodeId: PublicKey, channels: Iterable[ChannelAnnouncement]): Boolean = channels.exists(c => isRelatedTo(c, nodeId))

  def isStale(u: ChannelUpdate): Boolean = isStale(u.timestamp)

  def isStale(timestamp: Long): Boolean = {
    // BOLT 7: "nodes MAY prune channels should the timestamp of the latest channel_update be older than 2 weeks (1209600 seconds)"
    // but we don't want to prune brand new channels for which we didn't yet receive a channel update
    val staleThresholdSeconds = Platform.currentTime / 1000 - 1209600
    timestamp < staleThresholdSeconds
  }

  def isAlmostStale(timestamp: Long): Boolean = {
    // we define almost stale as 2 weeks minus 4 days (
    val staleThresholdSeconds = Platform.currentTime / 1000 - 864000
    timestamp < staleThresholdSeconds
  }

  // maximum number of stale channels that we will prune on startup
  val MAX_PRUNE_COUNT = 1000

  /**
    * Is stale a channel that:
    * (1) is older than 2 weeks (2*7*144 = 2016 blocks)
    * AND
    * (2) has no channel_update younger than 2 weeks
    *
    * @param channel
    * @param update1_opt update corresponding to one side of the channel, if we have it
    * @param update2_opt update corresponding to the other side of the channel, if we have it
    * @return
    */
  def isStale(channel: ChannelAnnouncement, update1_opt: Option[ChannelUpdate], update2_opt: Option[ChannelUpdate]): Boolean = {
    // BOLT 7: "nodes MAY prune channels should the timestamp of the latest channel_update be older than 2 weeks (1209600 seconds)"
    // but we don't want to prune brand new channels for which we didn't yet receive a channel update, so we keep them as long as they are less than 2 weeks (2016 blocks) old
    val staleThresholdBlocks = Globals.blockCount.get() - 2016
    val TxCoordinates(blockHeight, _, _) = ShortChannelId.coordinates(channel.shortChannelId)
    blockHeight < staleThresholdBlocks && update1_opt.map(isStale).getOrElse(true) && update2_opt.map(isStale).getOrElse(true)
  }

  def getStaleChannels(channels: Iterable[ChannelAnnouncement], updates: Map[ChannelDesc, ChannelUpdate]): Iterable[ShortChannelId] = {
    val staleChannels = channels.filter { c =>
      val update1 = updates.get(ChannelDesc(c.shortChannelId, c.nodeId1, c.nodeId2))
      val update2 = updates.get(ChannelDesc(c.shortChannelId, c.nodeId2, c.nodeId1))
      isStale(c, update1, update2)
    }
    staleChannels.map(_.shortChannelId)
  }

  /**
    * Filters channels that we want to send to nodes asking for a channel range
    */
  def keep(firstBlockNum: Long, numberOfBlocks: Long, id: ShortChannelId): Boolean = {
    val TxCoordinates(height, _, _) = ShortChannelId.coordinates(id)
    height >= firstBlockNum && height <= (firstBlockNum + numberOfBlocks)
  }

  /**
    * Returns overall progress on synchronization
    *
    * @param sync
    * @return a sync progress indicator (1 means fully synced)
    */
  def syncProgress(sync: Map[PublicKey, Sync]): SyncProgress = {
    //NB: progress is in terms of requests, not individual channels
    val (pending, total) = sync.foldLeft((0, 0)) {
      case ((p, t), (_, sync)) => (p + sync.pending.size, t + sync.total)
    }
    if (total == 0) {
      SyncProgress(1)
    } else {
      SyncProgress((total - pending) / (1.0 * total))
    }
  }

  /**
    * This method is used after a payment failed, and we want to exclude some nodes that we know are failing
    */
  def getIgnoredChannelDesc(updates: Map[ChannelDesc, ChannelUpdate], ignoreNodes: Set[PublicKey]): Iterable[ChannelDesc] = {
    val desc = if (ignoreNodes.isEmpty) {
      Iterable.empty[ChannelDesc]
    } else {
      // expensive, but node blacklisting shouldn't happen often
      updates.keys.filter(desc => ignoreNodes.contains(desc.a) || ignoreNodes.contains(desc.b))
    }
    desc
  }

  /**
    *
    * @param channels id -> announcement map
    * @param updates  channel updates
    * @param id       short channel id
    * @return the timestamp of the most recent update for this channel id, 0 if we don't have any
    */
  def getTimestamp(channels: SortedMap[ShortChannelId, ChannelAnnouncement], updates: Map[ChannelDesc, ChannelUpdate])(id: ShortChannelId): Long = {
    val ca = channels(id)
    val opt1 = updates.get(ChannelDesc(ca.shortChannelId, ca.nodeId1, ca.nodeId2))
    val opt2 = updates.get(ChannelDesc(ca.shortChannelId, ca.nodeId2, ca.nodeId1))
    val timestamp = (opt1, opt2) match {
      case (Some(u1), Some(u2)) => Math.max(u1.timestamp, u2.timestamp)
      case (Some(u1), None) => u1.timestamp
      case (None, Some(u2)) => u2.timestamp
      case (None, None) => 0L
    }
    timestamp
  }

  def getChannelDigestInfo(channels: SortedMap[ShortChannelId, ChannelAnnouncement], updates: Map[ChannelDesc, ChannelUpdate])(shortChannelId: ShortChannelId): ShortChannelIdWithChecksums = {
    val c = channels(shortChannelId)
    val u1_opt = updates.get(ChannelDesc(c.shortChannelId, c.nodeId1, c.nodeId2))
    val u2_opt = updates.get(ChannelDesc(c.shortChannelId, c.nodeId2, c.nodeId1))
    val timestamp1 = u1_opt.map(_.timestamp).getOrElse(0L)
    val timestamp2 = u2_opt.map(_.timestamp).getOrElse(0L)
    val checksum1 = u1_opt.map(getChecksum).getOrElse(0L)
    val checksum2 = u2_opt.map(getChecksum).getOrElse(0L)
    ShortChannelIdWithChecksums(shortChannelId, timestamp1, timestamp2, checksum1, checksum2)
  }

  def getChecksum(u: ChannelUpdate): Long = {
    import u._
    val data = serializationResult(LightningMessageCodecs.channelUpdateChecksumCodec.encode(shortChannelId :: messageFlags :: channelFlags :: cltvExpiryDelta :: htlcMinimumMsat :: feeBaseMsat :: feeProportionalMillionths :: htlcMaximumMsat :: HNil))
    val checksum = new Adler32()
    checksum.update(data.data.toArray)
    checksum.getValue
  }

  case class ShortChannelIdsChunk(firstBlock: Long, numBlocks: Long, shortChannelIds: List[ShortChannelId])

  /**
    * Have to split ids because otherwise message could be too big
    * there could be several reply_channel_range messages for a single query
    *
    * @param shortChannelIds
    * @return
    */
  def split(shortChannelIds: SortedSet[ShortChannelId]): List[ShortChannelIdsChunk] = {
    // TODO: this is wrong because it can split blocks
    shortChannelIds
      .grouped(2000) // LN messages must fit in 65 Kb so we split ids into groups to make sure that the output message will be valid
      .toList
      .map { group =>
        // NB: group is never empty
        val firstBlock: Long = ShortChannelId.coordinates(group.head).blockHeight.toLong
        val numBlocks: Long = ShortChannelId.coordinates(group.last).blockHeight.toLong - firstBlock + 1
        ShortChannelIdsChunk(firstBlock, numBlocks, group.toList)
      }
  }

  def updateSync(syncMap: Map[PublicKey, Sync], remoteNodeId: PublicKey, pending: List[RoutingMessage]): (Map[PublicKey, Sync], Option[RoutingMessage]) = {
    pending match {
      case head +: rest =>
        // they may send back several reply_channel_range messages for a single query_channel_range query, and we must not
        // send another query_short_channel_ids query if they're still processing one
        syncMap.get(remoteNodeId) match {
          case None =>
            // we don't have a pending query with this peer, let's send it
            (syncMap + (remoteNodeId -> Sync(rest, pending.size)), Some(head))
          case Some(sync) =>
            // we already have a pending query with this peer, add missing ids to our "sync" state
            (syncMap + (remoteNodeId -> Sync(sync.pending ++ pending, sync.total + pending.size)), None)
        }
      case Nil =>
        // there is nothing to send
        (syncMap, None)
    }
  }

  /**
    * https://github.com/lightningnetwork/lightning-rfc/blob/master/04-onion-routing.md#clarifications
    */
  val ROUTE_MAX_LENGTH = 20

  // The default amount of routes we'll search for when findRoute is called
  val DEFAULT_ROUTES_COUNT = 3

  // The default allowed 'spread' between the cheapest route found an the others
  // routes exceeding this difference won't be considered as a valid result
  val DEFAULT_ALLOWED_SPREAD = 0.1D

  /**
    * Find a route in the graph between localNodeId and targetNodeId, returns the route.
    * Will perform a k-shortest path selection given the @param numRoutes and randomly select one of the result,
    * the 'route-set' from where we select the result is made of the k-shortest path given that none of them
    * exceeds a 10% spread with the cheapest route
    *
    * @param g
    * @param localNodeId
    * @param targetNodeId
    * @param amountMsat   the amount that will be sent along this route
    * @param numRoutes    the number of shortest-paths to find
    * @param extraEdges   a set of extra edges we want to CONSIDER during the search
    * @param ignoredEdges a set of extra edges we want to IGNORE during the search
    * @return the computed route to the destination @targetNodeId
    */
  def findRoute(g: DirectedGraph, localNodeId: PublicKey, targetNodeId: PublicKey, amountMsat: Long, numRoutes: Int, extraEdges: Set[GraphEdge] = Set.empty, ignoredEdges: Set[ChannelDesc] = Set.empty): Try[Seq[Hop]] = Try {
    if (localNodeId == targetNodeId) throw CannotRouteToSelf

    val foundRoutes = Graph.yenKshortestPaths(g, localNodeId, targetNodeId, amountMsat, ignoredEdges, extraEdges, numRoutes).toList match {
      case Nil => throw RouteNotFound
      case route :: Nil if route.path.isEmpty => throw RouteNotFound
      case foundRoutes => foundRoutes
    }

    // minimum cost
    val minimumCost = foundRoutes.head.weight

    // routes paying at most minimumCost + 10%
    val eligibleRoutes = foundRoutes.filter(_.weight <= (minimumCost + minimumCost * DEFAULT_ALLOWED_SPREAD).round)
    Random.shuffle(eligibleRoutes).head.path.map(graphEdgeToHop)
  }
}
