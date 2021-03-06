package org.specs2
package specification
package process

import java.util.concurrent.TimeoutException

import scalaz.{Failure => _, Success => _, _}
import Scalaz._
import specification.core._
import org.specs2.time.SimpleTimer

import scala.concurrent._
import duration._
import control._
import producer._
import producers._
import Actions._
import org.specs2.control.eff.syntax.all._
import org.specs2.execute.{Result, Skipped, Success, Error}

/**
 * Functions for executing fragments.
 *
 * The default execution model executes all examples concurrently and uses steps as
 * "join" points
 *
 */
trait Executor {

  /**
   * execute fragments:
   *
   *  - filter the ones that the user wants to keep
   *  - sequence the execution so that only parts in between steps are executed concurrently
   */
  def execute(env: Env): AsyncTransducer[Fragment, Fragment]
}

/**
 * Default execution for specifications:
 * 
 *  - concurrent by default
 *  - using steps for synchronisation points
 */
trait DefaultExecutor extends Executor {

  /**
   * execute fragments:
   *
   *  - filter the ones that the user wants to keep
   *  - sequence the execution so that only parts in between steps are executed concurrently
   */
  def execute(env: Env): AsyncTransducer[Fragment, Fragment] = { contents: AsyncStream[Fragment] =>
    execute1(env)(contents).andFinally(protect(env.shutdown))
  }

  /**
   * execute fragments possibly with a recursive call to execute1.
   *
   * The difference with `execute` is that `execute` shuts down the environment when the process is finished
   */
  def execute1(env: Env): AsyncTransducer[Fragment, Fragment] = { contents: AsyncStream[Fragment] =>
    sequencedExecution(env)(contents).flatMap(executeOnline(env))
  }

  /**
   * execute fragments, making sure that:
   *
   *  - "join" points are respected, i.e. when a Fragment is a join we must make sure that all previously
   *    executing fragments have finished their execution
   *
   *  - the fragments execute sequentially when args.sequential
   *
   *  - the execution stops if one fragment indicates that the result of the previous executions is not correct
   */
  def sequencedExecution(env: Env): AsyncTransducer[Fragment, Fragment] = {
    type S = (Vector[Fragment], Result, Boolean)
    val init: S = (Vector.empty, Success(), false)
    val arguments = env.arguments

    def executeFragments(fs: Seq[Fragment], timeout: Option[FiniteDuration] = None): AsyncStream[Fragment] =
      if (arguments.sequential) emitEff(fs.toList.traverse(f => executeOneFragment(f, timeout)))
      else                      emitEff(fs.toList.traverseA(f => executeOneFragment(f, timeout)))

    def executeOneFragment(f: Fragment, timeout: Option[FiniteDuration] = None): Action[Fragment] = {
      if (arguments.sequential) asyncDelayAction(executeFragment(env)(f))
      else                      asyncForkAction(executeFragment(env)(f), timeout).asyncAttempt.map {
        case -\/(t: TimeoutException) => executeFragment(env)(f.setExecution(Execution.result(Skipped("timeout"+timeout.map(" after "+_).getOrElse("")))))
        case -\/(t)                   => executeFragment(env)(f.setExecution(Execution.result(Error(t))))
        case \/-(f1)                  => f1
      }
    }

    val last: S => AsyncStream[Fragment] = {
      case (fs, previousResults, mustStop) =>
        if (mustStop) emit(fs.toList.map(_.skip))
        else          executeFragments(fs, env.timeout)
    }

    transducers.producerStateEff(init, Option(last)) { case (fragment, (fragments, previousResults, mustStop)) =>
      val timeout = env.timeout.orElse(fragment.execution.timeout)

      def stopAll(previousResults: Result, fragment: Fragment): Boolean = {
        mustStop ||
        arguments.stopOnFail && previousResults.isFailure ||
        arguments.stopOnSkip && previousResults.isSkipped ||
        fragment.execution.nextMustStopIf(previousResults) ||
        fragment.executionFatalOrResult.isLeft
      }

      if (arguments.skipAll || mustStop)
        ok((one(if (fragment.isExecutable) fragment.skip else fragment), (Vector.empty, previousResults, mustStop)))
      else if (arguments.sequential)
        executeOneFragment(fragment, timeout).flatMap { f =>
          ok((one(f), (Vector.empty, previousResults, stopAll(f.executionResult, f))))
        }
      else {
        if (fragment.execution.mustJoin) {
          executeFragments(fragments, timeout).run.flatMap {
            case Done() =>
              executeOneFragment(fragment, timeout).flatMap { step =>
                ok((one(step), (Vector.empty, Success(), stopAll(previousResults, step))))
              }

            case producer.One(f) =>
              executeOneFragment(fragment, timeout).flatMap { step =>
                ok((oneOrMore(f, List(step)), (Vector.empty, Success(), stopAll(f.executionResult |+| previousResults, step))))
              }

            case fs @ More(as, next) =>
              executeOneFragment(fragment, timeout).flatMap { step =>
                ok((emitAsync(as:_*) append next append one(step),
                  (Vector.empty, Success(), stopAll(as.foldMap(_.executionResult) |+| previousResults, step))))
              }
          }
        }
        else if (fragments.count(_.isExecutable) >= arguments.threadsNb)
          executeFragments(fragments :+ fragment, timeout).run.flatMap {
            case Done()          => ok((done, (Vector.empty, previousResults, mustStop)))
            case producer.One(f) => ok((one(f), (Vector.empty, f.executionResult |+| previousResults, mustStop)))
            case More(as, next)  => ok((emitAsync(as:_*) append next, (Vector.empty, as.foldMap(_.executionResult) |+| previousResults, mustStop)))
          }
        else
          ok((done[ActionStack, Fragment], (fragments :+ fragment, previousResults, mustStop)))
      }
    }

  }

  /** execute one fragment */
  def executeFragment(env: Env) = (fragment: Fragment) => {
    fragment.updateExecution { execution =>
      val timer = (new SimpleTimer).start
      execution.execute(env).setExecutionTime(timer.stop)
    }
  }

  def executeOnline(env: Env): Fragment => AsyncStream[Fragment] = { fragment: Fragment =>
    fragment.execution.continuation match {
      case Some(continue) =>
        continue(fragment.executionResult).cata(
          fs => emitAsyncDelayed(fragment) append execute1(env)(fs.contents),
          emitAsyncDelayed(fragment))

      case None => emitAsyncDelayed(fragment)
    }
  }
}

/**
 * helper functions for executing fragments
 */
object DefaultExecutor extends DefaultExecutor {

  def executeSpecWithoutShutdown(spec: SpecStructure, env: Env): SpecStructure =
    spec.|>((contents: AsyncStream[Fragment]) => contents |> sequencedExecution(env))

  def executeSpec(spec: SpecStructure, env: Env): SpecStructure = {
    spec.|>((contents: AsyncStream[Fragment]) => (contents |> sequencedExecution(env)).thenFinally(protect(env.shutdown)))
  }

  def runSpec(spec: SpecStructure, env: Env): List[Fragment] =
    runAction(executeSpec(spec, env).contents.runList).toOption.getOrElse(Nil)

  def runSpecification(spec: SpecificationStructure) = {
    lazy val structure = spec.structure(Env())
    val env = Env(arguments = structure.arguments)
    runSpec(structure, env)
  }

  /** only to be used in tests */
  def executeFragments(fs: Fragments)(implicit env: Env = Env()) = executeAll(fs.fragments:_*)
  def executeAll(seq: Fragment*)(implicit env: Env = Env()) = executeSeq(seq)(env)
  def execute(f: Fragment)(implicit env: Env = Env()) = executeAll(f)(env).headOption.getOrElse(f)

  /** only to be used in tests */
  def executeSeq(seq: Seq[Fragment])(implicit env: Env = Env()): List[Fragment] =
    try runAction((emitAsync(seq:_*) |> sequencedExecution(env)).runList).toOption.getOrElse(Nil)
    finally env.shutdown

  /** synchronous execution */
  def executeFragments1 =
    transducers.transducer[ActionStack, Fragment, Fragment](executeFragment(Env()))

  /** synchronous execution with a specific environment */
  def executeFragments1(env: Env) = transducers.transducer[ActionStack, Fragment, Fragment](executeFragment(env))
}
