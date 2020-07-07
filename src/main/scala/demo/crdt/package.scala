package demo

import akka.cluster.ddata.ReplicatedData

package object crdt {

  // format: OFF
  /**
    *
    * When the job is submitted, it is recorded as "New”. Then it becomes "Started", "Running" and finally "Finished"
    * (happy case scenario, ignoring failures for the sake of simplicity here).
    * But another possibility is that the client that started the testcase decides to aborted its execution because the computation is no longer needed.
    *
    * Performance test case status values as CRDTs with their merge ordering indicated by state progression arrows:
    * walking in the direction of the arrows goes from predecessor to successor in the merge order.
    *
    *                           +-------> Failed ----+
    *                           |                    |
    *                 +----->Running ---> Aborted ---+
    *                 |         |                    |
    * Idle -------->Started     +--------------------+-----> Finished
    *                 |                              |
    *                 +----->Rejected(over capacity)-+
    *
    *
    * Conflict resolution rules:
    *
    * merge(Running, Rejected) = Rejected
    * merge(Aborted, Rejected) = Aborted
    * merge(Failed, Rejected)  = Failed
    * merge(Failed, Aborted)   = Aborted
    *
    * Why?
    * If the task was rejected on one node, it hasn't passed the capacity validation, so we will pick Rejected status.
    *
    *
    */
  // format: ON
  val Idle: Status     = Status("Idle")(Set.empty, Set(Started))
  val Started: Status  = Status("Started")(Set(Idle), Set(Running, Rejected))
  val Running: Status  = Status("Running")(Set(Started), Set(Finished, Failed, Aborted))
  val Rejected: Status = Status("Rejected")(Set(Started), Set(Finished))
  val Aborted: Status  = Status("Aborted")(Set(Running), Set(Finished))
  val Failed: Status   = Status("Failed")(Set(Running), Set(Finished))
  val Finished: Status = Status("Finished")(Set(Running, Failed, Aborted, Rejected), Set.empty)

  val RunningVsRejectedConflict = Set(Running, Rejected)
  val AbortedVsRejectedConflict = Set(Aborted, Rejected)
  val FailedVsRejectedConflict  = Set(Failed, Rejected)
  val FailedVsAbortedConflict   = Set(Failed, Aborted)

  //instances for tests
  /*
  val idle     = ReplicatedJob(Idle)
  val started  = ReplicatedJob(Started)
  val running  = ReplicatedJob(Running)
  val rejected = ReplicatedJob(Rejected)
  val aborted  = ReplicatedJob(Aborted)
  val failed   = ReplicatedJob(Failed)
  val finished = ReplicatedJob(Finished)
   */

  final case class Status(name: String)(preds: ⇒ Set[Status], succs: ⇒ Set[Status]) extends ReplicatedData {
    type T = Status
    lazy val predecessors = preds
    lazy val successors   = succs

    override def merge(that: Status): Status =
      Set(this, that) match {
        case RunningVsRejectedConflict ⇒ Rejected
        case AbortedVsRejectedConflict ⇒ Aborted
        case FailedVsRejectedConflict  ⇒ Failed
        case FailedVsAbortedConflict   ⇒ Aborted
        case _                         ⇒ mergeLoop(this, that)
      }

    private def mergeLoop(left: Status, right: Status): Status = {
      def loop(candidate: Status, exclude: Set[Status]): Status =
        if (isSuccessor(candidate, left, exclude))
          candidate
        else {
          val nextExclude = exclude + candidate
          val branches    = candidate.successors.map(succ ⇒ loop(succ, nextExclude))
          branches.reduce((l, r) ⇒ if (isSuccessor(l, r, nextExclude)) r else l)
        }

      def isSuccessor(candidate: Status, fixed: Status, exclude: Set[Status]): Boolean =
        if (candidate == fixed) true
        else {
          val toSearch = candidate.predecessors -- exclude
          toSearch.exists(pred ⇒ isSuccessor(pred, fixed, exclude))
        }

      loop(right, Set.empty)
    }
  }

  final case class ReplicatedJob(status: Status) extends ReplicatedData {
    type T = ReplicatedJob

    override def merge(that: ReplicatedJob): ReplicatedJob = {
      val mergedStatus = status.merge(that.status)
      ReplicatedJob(mergedStatus)
    }
  }

}
