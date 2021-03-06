/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster.oldrouting

import language.postfixOps
import java.lang.management.ManagementFactory
import scala.concurrent.Await
import scala.concurrent.duration._
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory

import akka.actor._
import akka.cluster.Cluster
import akka.cluster.MultiNodeClusterSpec
import akka.cluster.NodeMetrics
import akka.pattern.ask
import akka.remote.testkit.{ MultiNodeSpec, MultiNodeConfig }
import akka.routing.CurrentRoutees
import akka.routing.FromConfig
import akka.routing.RouterRoutees
import akka.testkit.{ LongRunningTest, DefaultTimeout, ImplicitSender }
import akka.cluster.routing._

object AdaptiveLoadBalancingRouterMultiJvmSpec extends MultiNodeConfig {

  class Routee extends Actor {
    def receive = {
      case _ ⇒ sender() ! Reply(Cluster(context.system).selfAddress)
    }
  }

  class Memory extends Actor with ActorLogging {
    var usedMemory: Array[Array[Int]] = _
    def receive = {
      case AllocateMemory ⇒
        val heap = ManagementFactory.getMemoryMXBean.getHeapMemoryUsage
        // getMax can be undefined (-1)
        val max = math.max(heap.getMax, heap.getCommitted)
        val used = heap.getUsed
        log.debug("used heap before: [{}] bytes, of max [{}]", used, heap.getMax)
        // allocate 70% of free space
        val allocateBytes = (0.7 * (max - used)).toInt
        val numberOfArrays = allocateBytes / 1024
        usedMemory = Array.ofDim(numberOfArrays, 248) // each 248 element Int array will use ~ 1 kB
        log.debug("used heap after: [{}] bytes", ManagementFactory.getMemoryMXBean.getHeapMemoryUsage.getUsed)
        sender() ! "done"
    }
  }

  case object AllocateMemory
  final case class Reply(address: Address)

  val first = role("first")
  val second = role("second")
  val third = role("third")

  commonConfig(debugConfig(on = false).withFallback(ConfigFactory.parseString("""
      akka.cluster.metrics.collect-interval = 1s
      akka.cluster.metrics.gossip-interval = 1s
      akka.cluster.metrics.moving-average-half-life = 2s
      akka.actor.deployment {
        /router3 = {
          router = adaptive
          metrics-selector = cpu
          nr-of-instances = 9
        }
        /router4 = {
          router = adaptive
          metrics-selector = "akka.cluster.routing.TestCustomMetricsSelector"
          nr-of-instances = 10
          cluster {
            enabled = on
            max-nr-of-instances-per-node = 2
          }
        }
      }
    """)).withFallback(MultiNodeClusterSpec.clusterConfig))

}

class TestCustomMetricsSelector(config: Config) extends MetricsSelector {
  override def weights(nodeMetrics: Set[NodeMetrics]): Map[Address, Int] = Map.empty
}

class AdaptiveLoadBalancingRouterMultiJvmNode1 extends AdaptiveLoadBalancingRouterSpec
class AdaptiveLoadBalancingRouterMultiJvmNode2 extends AdaptiveLoadBalancingRouterSpec
class AdaptiveLoadBalancingRouterMultiJvmNode3 extends AdaptiveLoadBalancingRouterSpec

abstract class AdaptiveLoadBalancingRouterSpec extends MultiNodeSpec(AdaptiveLoadBalancingRouterMultiJvmSpec)
  with MultiNodeClusterSpec
  with ImplicitSender with DefaultTimeout {
  import AdaptiveLoadBalancingRouterMultiJvmSpec._

  def currentRoutees(router: ActorRef) =
    Await.result(router ? CurrentRoutees, timeout.duration).asInstanceOf[RouterRoutees].routees

  def receiveReplies(expectedReplies: Int): Map[Address, Int] = {
    val zero = Map.empty[Address, Int] ++ roles.map(address(_) -> 0)
    (receiveWhile(5 seconds, messages = expectedReplies) {
      case Reply(address) ⇒ address
    }).foldLeft(zero) {
      case (replyMap, address) ⇒ replyMap + (address -> (replyMap(address) + 1))
    }
  }

  /**
   * Fills in self address for local ActorRef
   */
  def fullAddress(actorRef: ActorRef): Address = actorRef.path.address match {
    case Address(_, _, None, None) ⇒ cluster.selfAddress
    case a                         ⇒ a
  }

  def startRouter(name: String): ActorRef = {
    val router = system.actorOf(Props[Routee].withRouter(ClusterRouterConfig(
      local = AdaptiveLoadBalancingRouter(HeapMetricsSelector),
      settings = ClusterRouterSettings(totalInstances = 10, maxInstancesPerNode = 1, useRole = None))), name)
    // it may take some time until router receives cluster member events
    awaitAssert { currentRoutees(router).size should be(roles.size) }
    currentRoutees(router).map(fullAddress).toSet should be(roles.map(address).toSet)
    router
  }

  "A cluster with a AdaptiveLoadBalancingRouter" must {
    "start cluster nodes" taggedAs LongRunningTest in {
      awaitClusterUp(roles: _*)
      enterBarrier("after-1")
    }

    "use all nodes in the cluster when not overloaded" taggedAs LongRunningTest in {
      runOn(first) {
        val router1 = startRouter("router1")

        // collect some metrics before we start
        Thread.sleep(cluster.settings.MetricsInterval.toMillis * 10)

        val iterationCount = 100
        1 to iterationCount foreach { _ ⇒
          router1 ! "hit"
          // wait a while between each message, since metrics is collected periodically
          Thread.sleep(10)
        }

        val replies = receiveReplies(iterationCount)

        replies(first) should be > (0)
        replies(second) should be > (0)
        replies(third) should be > (0)
        replies.values.sum should be(iterationCount)

      }

      enterBarrier("after-2")
    }

    "prefer node with more free heap capacity" taggedAs LongRunningTest in {
      System.gc()
      enterBarrier("gc")

      runOn(second) {
        within(20.seconds) {
          system.actorOf(Props[Memory], "memory") ! AllocateMemory
          expectMsg("done")
        }
      }
      enterBarrier("heap-allocated")

      runOn(first) {
        val router2 = startRouter("router2")

        // collect some metrics before we start
        Thread.sleep(cluster.settings.MetricsInterval.toMillis * 10)

        val iterationCount = 3000
        1 to iterationCount foreach { _ ⇒
          router2 ! "hit"
        }

        val replies = receiveReplies(iterationCount)

        replies(third) should be > (replies(second))
        replies.values.sum should be(iterationCount)

      }

      enterBarrier("after-3")
    }

    "create routees from configuration" taggedAs LongRunningTest in {
      runOn(first) {
        val router3 = system.actorOf(Props[Memory].withRouter(FromConfig()), "router3")
        // it may take some time until router receives cluster member events
        awaitAssert { currentRoutees(router3).size should be(9) }
        currentRoutees(router3).map(fullAddress).toSet should be(Set(address(first)))
      }
      enterBarrier("after-4")
    }

    "create routees from cluster.enabled configuration" taggedAs LongRunningTest in {
      runOn(first) {
        val router4 = system.actorOf(Props[Memory].withRouter(FromConfig()), "router4")
        // it may take some time until router receives cluster member events
        awaitAssert { currentRoutees(router4).size should be(6) }
        currentRoutees(router4).map(fullAddress).toSet should be(Set(
          address(first), address(second), address(third)))
      }
      enterBarrier("after-5")
    }
  }
}
