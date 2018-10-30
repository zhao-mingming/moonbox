/*-
 * <<
 * Moonbox
 * ==
 * Copyright (C) 2016 - 2018 EDP
 * ==
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * >>
 */

package moonbox.yarnapp

import java.util.concurrent.Executors

import akka.actor.{Actor, ActorRef, PoisonPill}
import moonbox.common.{MbConf, MbLogging}
import moonbox.core.datasys.{DataSystem, Insertable}
import moonbox.core.{ColumnSelectPrivilegeException, MbSession, TableInsertPrivilegeChecker, TableInsertPrivilegeException}
import moonbox.protocol.app.JobState.JobState
import moonbox.protocol.app._
import org.apache.spark.sql.optimizer.WholePushdown
import org.apache.spark.sql.{Row, SaveMode}

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

class Runner(conf: MbConf, mbSession: MbSession) extends Actor with MbLogging {
	private val awaitTimeout = new FiniteDuration(20, SECONDS)
	private implicit val catalogSession = mbSession.userContext
	private var currentJob: TaskInfo = _
	private val resultSchemaHashMap = mutable.HashMap.empty[String, String]
	private val resultDataHashMap = mutable.HashMap.empty[String, Iterator[Seq[Any]]]
    private implicit val contextExecutor = {
        val executor = Executors.newFixedThreadPool(10)  //poolsize is temporarily set 10
        ExecutionContext.fromExecutor(executor)
    }

	override def receive: Receive = {
		case RunJob(taskInfo) =>
			logInfo(s"Runner::RunJob  $taskInfo")
			currentJob = taskInfo
			val target = sender()
			run(taskInfo, target).onComplete {
				case Success(data) =>
					successCallback(taskInfo.jobId, taskInfo.seq, data, target, taskInfo.sessionId.isEmpty)
				case Failure(e) =>
					e.printStackTrace()
                    if(e!= null && e.getMessage.contains("cancelled job")){
                        cancelCallback(taskInfo.jobId, taskInfo.seq, e, target, false) //TaskKilledException can not catch
                    } else{
                        failureCallback(taskInfo.jobId, taskInfo.seq, e, target, taskInfo.sessionId.isEmpty)
                    }
			}
		case CancelJob(jobId) =>
            logInfo(s"Runner::CancelJob [WARNING] !!! $jobId")
			mbSession.cancelJob(jobId)
		case KillRunner =>
			logInfo(s"Runner::KillRunner $currentJob")
			if(currentJob == null || currentJob.sessionId.isDefined) {  //if a runner have not a job OR it is an adhoc, release resources
				clean(JobState.KILLED)
				self ! PoisonPill
			}

		case FetchDataFromRunner(_, jobId, fetchSize) =>
			val target = sender()
			Future {
				fetchData(jobId, fetchSize, target)
			}
	}

	def run(taskInfo: TaskInfo, target: ActorRef): Future[JobResult] = {
		Future {
			println(s"run $taskInfo")
			taskInfo.task match {
				//case runnable: MbRunnableCommand =>
				//	val row = runnable.run(mbSession)
				//	DirectData(row.map(_.toSeq.map(_.toString)))
				case tempView: CreateTempViewTask =>
					createTempView(tempView)
				case query: QueryTask =>
					mqlQuery(query, taskInfo.jobId)
				case insert: InsertIntoTask =>
					insertInto(insert)
				case _ => throw new Exception("Unsupported command.")

			}
		}
	}

	def fetchData(jobId: String, fetchSize: Long, target: ActorRef) = {
		if (resultSchemaHashMap.get(jobId).isDefined && resultDataHashMap.get(jobId).isDefined) {

			val schema = resultSchemaHashMap(jobId)
			val buffer: ArrayBuffer[Seq[Any]] = ArrayBuffer.empty[Seq[Any]]
			val iterator = resultDataHashMap(jobId)

			var startSize: Long = 0
			while (iterator.hasNext && startSize < fetchSize) {
				buffer += iterator.next()
				startSize += 1
			}

			if (!iterator.hasNext) {
				println(s"remove jobId from result hashMap $jobId")
				resultDataHashMap.remove(jobId)
				resultSchemaHashMap.remove(jobId)
				target ! FetchedDataFromRunner(jobId, schema, buffer, false)
			} else {
				target ! FetchedDataFromRunner(jobId, schema, buffer, true)
			}
		}
		else {
			target ! FetchDataFromRunnerFailed(jobId, s"jobId $jobId does not exist or has been removed in yarn.")
		}
	}

	def createTempView(tempView: CreateTempViewTask): JobResult = {
		val optimized = mbSession.optimizedPlan(tempView.query)
		val plan = mbSession.pushdownPlan(optimized, pushdown = false)
		val df = mbSession.toDF(plan)
		if (tempView.isCache) {
			df.cache()
		}
		if (tempView.replaceIfExists) {
			df.createOrReplaceTempView(tempView.name)
		} else {
			df.createTempView(tempView.name)
		}
		UnitData
	}

	def mqlQuery(query: QueryTask, jobId: String): JobResult = {
		val optimized = mbSession.optimizedPlan(query.query)
		var iter: scala.Iterator[Row] = null

		try {
            mbSession.mixcal.setJobGroup(jobId)  //cancel
			val plan = mbSession.pushdownPlan(optimized)
			plan match {
				case WholePushdown(child, queryable) =>
                    logInfo(s"WholePushdown $query")
					iter = mbSession.toDT(child, queryable).iter
				case _ =>
					iter = mbSession.toDF(plan).collect().iterator

			}
		} catch {
			case e: ColumnSelectPrivilegeException =>
				throw e
			case e: Throwable =>
                if (e.getMessage.contains("cancelled job")) {
                    throw e
                } else {
					e.printStackTrace()
                    logWarning(s"Execute push failed with ${e.getMessage}. Retry without pushdown.")
                    val plan = mbSession.pushdownPlan(optimized, pushdown = false)
                    plan match {
                        case WholePushdown(child, queryable) =>
							iter = mbSession.toDT(child, queryable).iter
                        case _ =>
							iter = mbSession.toDF(plan).collect().iterator
                    }
                }
		}
		resultSchemaHashMap.put(jobId, optimized.schema.json) //save schema

		val buffer: ArrayBuffer[Seq[Any]] = ArrayBuffer.empty[Seq[Any]]
		while (iter.hasNext) {
			buffer += iter.next().toSeq.map { elem =>
				if ( elem == null) { "" }
				else elem
			}
		}
		resultDataHashMap.put(jobId, buffer.iterator)  //save data
		UnitData
	}

	def insertInto(insert: InsertIntoTask): JobResult = {
		// TODO sink is table or view
		val sinkCatalogTable = mbSession.getCatalogTable(insert.table, insert.database)
		val options = sinkCatalogTable.properties
		val sinkDataSystem = DataSystem.lookupDataSystem(options)
		val format = DataSystem.lookupDataSource(options("type"))
		val saveMode = if (insert.overwrite) SaveMode.Overwrite else SaveMode.Append
		val optimized = mbSession.optimizedPlan(insert.query)
		try {
			val plan = mbSession.pushdownPlan(optimized)
			plan match {
				case WholePushdown(child, queryable) if sinkDataSystem.isInstanceOf[Insertable] =>
					val dataTable = mbSession.toDT(child, queryable)
					TableInsertPrivilegeChecker.intercept(mbSession, sinkCatalogTable, dataTable)
					.write().format(format).options(options).mode(saveMode).save()
				case _ =>
					val dataFrame = mbSession.toDF(plan)
					TableInsertPrivilegeChecker.intercept(mbSession, sinkCatalogTable, dataFrame).write.format(format).options(options).mode(saveMode).save()
			}
		} catch {
			case e: ColumnSelectPrivilegeException =>
				throw e
            case e: TableInsertPrivilegeException =>
                throw e
			case e: Throwable =>
				logWarning(e.getMessage)
				val plan = mbSession.pushdownPlan(optimized, pushdown = false)
				plan match {
					case WholePushdown(child, queryable) =>
						mbSession.toDF(child).write.format(format).options(options).mode(saveMode).save()
					case _ =>
						mbSession.toDF(plan).write.format(format).options(options).mode(saveMode).save()
				}
		}
		UnitData
	}

	private def clean(jobState: JobState): Unit = {
		Future {
			logInfo(s"Runner::clean $jobState start")
			mbSession.cancelJob(currentJob.jobId)
			// session.mixcal.sparkSession.sessionState.catalog.reset()
			logInfo(s"Runner::clean $jobState end")
		}
	}

	private def successCallback(jobId: String, seqNum: Int, result: JobResult, requester: ActorRef, shutdown: Boolean): Unit = {
		requester ! JobStateChanged(jobId, seqNum, JobState.SUCCESS, result)
		if (shutdown) {
			clean(JobState.SUCCESS)
			self ! PoisonPill
		}
	}

	private def failureCallback(jobId: String, seqNum: Int, e: Throwable, requester: ActorRef, shutdown: Boolean): Unit = {
		val errorMessage = Option(e.getCause).map(_.getMessage).getOrElse(e.getMessage)
		logError(e.getStackTrace.map(_.toString).mkString("\n"))
		logError(errorMessage)
		requester ! JobStateChanged(jobId, seqNum, JobState.FAILED, Failed(errorMessage))
		if (shutdown) {
			clean(JobState.FAILED)
			self ! PoisonPill
		}
	}

    private def cancelCallback(jobId: String, seqNum: Int, e: Throwable, requester: ActorRef, shutdown: Boolean): Unit = {
        logWarning(e.getStackTrace.map(_.toString).mkString("\n"))
        requester ! JobStateChanged(jobId, seqNum, JobState.KILLED, Failed(e.getMessage))
        if (shutdown) {
            clean(JobState.KILLED)
            self ! PoisonPill
        }
    }

}