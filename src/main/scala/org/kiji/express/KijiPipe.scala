/**
 * (c) Copyright 2013 WibiData, Inc.
 *
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
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

package org.kiji.express

import cascading.flow.Flow
import cascading.pipe.Pipe
import com.twitter.scalding.Args
import com.twitter.scalding.Job
import com.twitter.scalding.Mode

import org.kiji.express.repl.ExpressShell
import org.kiji.express.repl.Implicits

/**
 * A class that adds Kiji-specific functionality to a Cascading pipe. This includes running pipes
 * outside of the context of a Scalding Job.
 *
 * A `KijiPipe` should be obtained by end-users during the course of authoring a Scalding flow via
 * an implicit conversion available in [[org.kiji.express.repl.Implicits]].
 *
 * @param pipe enriched with extra functionality.
 */
class KijiPipe(private val pipe: Pipe) {
  /**
   * Gets a job that can be used to run the data pipeline.
   *
   * @param args that should be used to construct the job.
   * @return a job that can be used to run the data pipeline.
   */
  private[express] def getJob(args: Args): Job = new Job(args) {
    // The job's constructor should evaluate to the pipe to run.
    pipe

    /**
     *  The flow definition used by this job, which should be the same as that used by the user
     *  when creating their pipe.
     */
    override implicit val flowDef = Implicits.flowDef

    /**
     * Obtains a configuration used when running the job.
     *
     * This overridden method uses the same configuration as a standard Scalding job,
     * but adds a jar containing compiled REPL code to the distributed cache if the REPL is
     * running.
     *
     * @param mode used to run the job (either local or hadoop).
     * @return the configuration that should be used to run the job.
     */
    override def config(implicit mode: Mode): Map[AnyRef, AnyRef] = {
      val configuration = super.config(mode)
      // If the REPL is running, we should add tmpjars passed in from the command line,
      // and a jar of REPL code, to the distributed cache of jobs run through the REPL.
      val replCodeJar = ExpressShell.createReplCodeJar()
      if (replCodeJar.isDefined) {
        def appendComma(str: Any): String = str.toString + ","
        val tmpJarsConfiguration = Map("tmpjars" -> {
            // Use tmpjars already in the configuration.
            configuration
                .get("tmpjars")
                .map(appendComma)
                .getOrElse("") +
            // And tmpjars passed to ExpressShell from the command line when started.
            ExpressShell.tmpjars
                .map(appendComma)
                .getOrElse("") +
            // And a jar of code compiled by the REPL.
            "file://" + replCodeJar.get.getAbsolutePath
        })
        configuration ++ tmpJarsConfiguration
      } else {
        configuration
      }
    }

    /**
     * Builds a flow from the flow definition used when creating the pipeline run by this job.
     *
     * This overridden method operates the same as that of the super class,
     * but clears the implicit flow definition defined in [[org.kiji.express.repl.Implicits]]
     * after the flow has been built from the flow definition. This allows additional pipelines
     * to be constructed and run after the pipeline encapsulated by this job.
     *
     * @param mode the mode in which the built flow will be run.
     * @return the flow created from the flow definition.
     */
    override def buildFlow(implicit mode: Mode): Flow[_] = {
      val flow = super.buildFlow(mode)
      Implicits.resetFlowDef()
      flow
    }
  }

  /**
   * Runs this pipe as a Scalding job.
   */
  def run() {
    getJob(new Args(Map())).run(Mode.mode)
  }
}