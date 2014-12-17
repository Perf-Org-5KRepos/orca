package com.netflix.spinnaker.orca.echo.spring

import groovy.json.JsonBuilder
import groovy.json.JsonSlurper
import com.netflix.spinnaker.orca.DefaultTaskResult
import com.netflix.spinnaker.orca.ExecutionStatus
import com.netflix.spinnaker.orca.Task
import com.netflix.spinnaker.orca.config.OrcaConfiguration
import com.netflix.spinnaker.orca.echo.EchoService
import com.netflix.spinnaker.orca.jackson.OrcaObjectMapper
import com.netflix.spinnaker.orca.pipeline.PipelineStarter
import com.netflix.spinnaker.orca.pipeline.SimpleStage
import com.netflix.spinnaker.orca.pipeline.persistence.ExecutionRepository
import com.netflix.spinnaker.orca.test.batch.BatchTestConfiguration
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.support.AbstractApplicationContext
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ContextConfiguration
import spock.lang.Shared
import spock.lang.Specification
import static org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD

/**
 * Most of what the listener does can be tested at a unit level, this is just to
 * ensure that we're making the right assumptions about the statuses we'll get
 * from Batch at runtime, etc.
 */
@ContextConfiguration(classes = [BatchTestConfiguration, OrcaConfiguration])
@DirtiesContext(classMode = AFTER_EACH_TEST_METHOD)
class EchoEventSpec extends Specification {

  public static
  final taskSuccess = new DefaultTaskResult(ExecutionStatus.SUCCEEDED)
  public static final taskFailed = new DefaultTaskResult(ExecutionStatus.FAILED)
  public static
  final taskMustRepeat = new DefaultTaskResult(ExecutionStatus.RUNNING)

  def echoService = Mock(EchoService)

  @Autowired AbstractApplicationContext applicationContext
  @Autowired PipelineStarter jobStarter
  @Autowired ExecutionRepository executionRepository

  def task1 = Mock(Task)
  def task2 = Mock(Task)

  @Shared json

  def setupSpec() {
    def config = [
        application: "app",
        stages     : [[type: "stage1"], [type: "stage2"]]
    ]
    json = new OrcaObjectMapper().writeValueAsString(config)
  }

  def setup() {
    applicationContext.beanFactory.with {
      registerSingleton "echoListener", new EchoNotifyingStageExecutionListener(executionRepository, echoService)

      [task1, task2].eachWithIndex { task, i ->
        def name = "stage${i + 1}"
        def stage = new SimpleStage(name, task)
        autowireBean stage
        registerSingleton name, stage
      }
    }
    jobStarter.initialize()
  }

  def "events are raised in the correct order"() {
    given:
    def events = collectEvents()

    and:
    task1.execute(_) >> taskSuccess
    task2.execute(_) >> taskSuccess

    when:
    jobStarter.start(json)

    then:
    events.details.type == ["orca:task:starting", "orca:task:complete"] * 2
  }

  def "when tasks repeat they don't send duplicate start events"() {
    given:
    def events = collectEvents()

    and:
    task1.execute(_) >>> [taskMustRepeat, taskMustRepeat, taskMustRepeat, taskSuccess]
    task2.execute(_) >> taskSuccess

    when:
    jobStarter.start(json)

    then:
    events.details.type == ["orca:task:starting", "orca:task:complete"] * 2
  }

  def "when tasks fail they still send end events"() {
    given:
    def events = collectEvents()

    and:
    task1.execute(_) >> taskFailed

    when:
    jobStarter.start(json)

    then:
    0 * task2.execute(_)

    and:
    events.details.type == ["orca:task:starting", "orca:task:failed"]
  }

  /**
   * Traps the events sent to echo.
   * @return a list that will collect the event data sent to echo.
   */
  private List<Map> collectEvents() {
    def events = []
    echoService.recordEvent(_) >> {
      events << it[0]
      null // don't need to actually return anything from the echo call
    }
    return events
  }
}