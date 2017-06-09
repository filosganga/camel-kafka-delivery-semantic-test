package com.filippodeluca.camel.test

import java.util.UUID
import java.util.concurrent.TimeoutException

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.client.WireMock._
import net.manub.embeddedkafka.{EmbeddedKafka, EmbeddedKafkaConfig}
import org.apache.camel.Exchange
import org.apache.camel.builder.RouteBuilder
import org.apache.camel.http.common.HttpMethods
import org.apache.camel.impl.DefaultCamelContext
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.StringSerializer
import org.scalatest._
import org.scalatest.concurrent.{Eventually, IntegrationPatience}


class CamelDeliverySpec extends WordSpec with Matchers with EmbeddedKafka with BeforeAndAfterEach with BeforeAndAfterAll with Eventually with IntegrationPatience {

  val wireMockServer: WireMockServer = new WireMockServer()

  private var _kafkaConfig: EmbeddedKafkaConfig = _

  implicit def kafkaConfig: EmbeddedKafkaConfig =
    Option(_kafkaConfig).getOrElse(throw new IllegalStateException("Kafka config not yet instantiated"))

  implicit val stringSerializer =
    new StringSerializer

  def kafkaBrokers(implicit kafkaConfig: EmbeddedKafkaConfig): String =
    s"localhost:${kafkaConfig.kafkaPort}"

  def kafkaGroupId(implicit kafkaConfig: EmbeddedKafkaConfig): String =
    kafkaConfig.customConsumerProperties(ConsumerConfig.GROUP_ID_CONFIG)

  def wireMockEndpoint = s"http://localhost:${wireMockServer.port()}"

  "The camel-kafka component" should {
    "commit when the messages are delivered" in withRunningKafka {

      val kafkaTopic = s"test-${UUID.randomUUID().toString}"

      createCustomTopic(kafkaTopic, partitions = 3)

      stubFor(WireMock.any(urlEqualTo("/test"))
        .willReturn(aResponse()
          .withStatus(200)
          .withBody("OK"))
      )

      val kafkaMessages = (1 to 3).map(_.toString)

      kafkaMessages.foreach(i => publishToKafka(kafkaTopic, i, i))

      withCamelRoute(kafkaTopic) {

        eventually {
          verify(kafkaMessages.size, postRequestedFor(urlEqualTo("/test")))
        }
      }

      // All the kafka messages should be committed after the camel route has been shut down
      a[TimeoutException] should be thrownBy consumeNumberStringMessagesFrom(kafkaTopic, kafkaMessages.size, autoCommit = false)

    }

    "not commit when the messages are not delivered" in withRunningKafka {

      val kafkaTopic = s"test-${UUID.randomUUID().toString}"

      createCustomTopic(kafkaTopic, partitions = 3)

      stubFor(WireMock.any(urlEqualTo("/test"))
        .willReturn(aResponse()
          .withFixedDelay(250)
          .withStatus(500)
          .withBody("Not very OK"))
      )

      val kafkaMessages = (1 to 3).map(_.toString)

      kafkaMessages.foreach(i => publishToKafka(kafkaTopic, i, i))

      withCamelRoute(kafkaTopic) {
        eventually {
          // The endpoint should receive at least the first request (that failed)
          WireMock.verify(1, postRequestedFor(urlEqualTo("/test")))
        }
      }

      // The First kafka message should be still there
      consumeFirstStringMessageFrom(kafkaTopic, autoCommit = false) shouldBe kafkaMessages.head
    }
  }

  def withCamelRoute[T](kafkaTopic: String)(b: => T): T = {
    val camelContext = new DefaultCamelContext

    camelContext.addRoutes(new RouteBuilder() {
      override def configure(): Unit = {

        // Need the brokers query param as well as the host:port in the URI ?!!!?!?!?
        val kafkaConnector = s"kafka:$kafkaTopic?brokers=$kafkaBrokers&groupId=$kafkaGroupId&autoOffsetReset=earliest&consumersCount=1&autoCommitEnable=false&breakOnFirstError=true"

        from(kafkaConnector)
          // with Transacted it fails to run
          // .transacted()
          .process((exchange: Exchange) => {
            val message = exchange.getIn
            val kafkaValue = message.getBody

            // The awesome documentation says you have to use constant("POST") but it does not work.
            exchange.getOut.setHeader(Exchange.HTTP_METHOD, HttpMethods.POST)
            exchange.getOut.setBody(kafkaValue)
          })
          .to(s"$wireMockEndpoint/test")
      }
    })

    try {
      camelContext.start()
      b
    } finally {
      camelContext.stop()
    }

  }

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    wireMockServer.start()
  }


  override protected def afterAll(): Unit = {
    wireMockServer.shutdown()
    super.afterAll()
  }

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    _kafkaConfig = EmbeddedKafkaConfig(customConsumerProperties = Map(ConsumerConfig.GROUP_ID_CONFIG -> s"test-${UUID.randomUUID().toString}"))
    wireMockServer.resetAll()
    WireMock.configureFor(wireMockServer.port())
  }

  override protected def afterEach(): Unit = {
    wireMockServer.resetAll()
    super.afterEach()
  }
}
