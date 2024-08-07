package scoreboard

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.http.scaladsl.server.Directives.{handleWebSocketMessages, path}
import akka.stream.ActorMaterializer
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{Flow, Sink, Source, SourceQueueWithComplete}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}
import scala.collection.JavaConverters._

import org.apache.kafka.clients.consumer.{KafkaConsumer, ConsumerRecords, ConsumerConfig}

import java.util.Properties
import java.time.Duration
import java.util.Scanner
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WebSocket object provides methods for managing WebSocket connections.
 */
object WebSocket {
  private var browserConnections: List[TextMessage => Unit] = List()

  def listen(): Flow[Message, Message, NotUsed] = {
    val inbound: Sink[Message, Any] = Sink.foreach(_ => ())
    val outbound: Source[Message, SourceQueueWithComplete[Message]] = Source.queue[Message](16, OverflowStrategy.fail)

    Flow.fromSinkAndSourceMat(inbound, outbound)((_, outboundMat) => {
      browserConnections ::= outboundMat.offer
      NotUsed
    })
  }

  def sendText(text: String): Unit = {
    for (connection <- browserConnections) connection(TextMessage.Strict(text))
  }
}

/**
 * ScoreboardServer is responsible for:
 *   - Establishing a WebSocket connection with the scoreboard UI.
 *   - Consuming score updates from a Kafka topic.
 *   - Broadcasting score updates to connected WebSocket clients.
 */
object ScoreboardServer extends App {
  implicit val actorSystem: ActorSystem = ActorSystem("system")
  implicit val materializer: ActorMaterializer = ActorMaterializer()

  val route = path("score") {
    handleWebSocketMessages(WebSocket.listen())
  }

  Http().bindAndHandle(route, "localhost", 8082).onComplete {
    case Success(binding)   =>
      println(s"Scoreboard Server online at ${binding.localAddress.getHostString}:${binding.localAddress.getPort}")
    case Failure(exception) => throw exception
  }

  // Kafka consumer
  val topic = "score"
  val kafkaServer = "localhost:9092"
  val kafkaProps = new Properties()
  kafkaProps.put("bootstrap.servers", kafkaServer)
  kafkaProps.put("group.id", s"$topic-group")
  kafkaProps.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer")
  kafkaProps.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer")

  val consumer = new KafkaConsumer[String, String](kafkaProps)

  // stop the server when ENTER is pressed on other thread
  val stopServerFlag = new AtomicBoolean(false)
  val inputThread = new Thread(() => {
    println("Press ENTER to stop the server...")
    val scanner = new Scanner(System.in)
    scanner.nextLine() // Wait for ENTER
    stopServerFlag.set(true)
  })
  inputThread.start()

  consumer.subscribe(java.util.Collections.singletonList(topic))
  try {
    while (!stopServerFlag.get()) {
      val records = consumer.poll(java.time.Duration.ofMillis(100))
      for (record <- records.asScala) {
        val message = record.value()
        WebSocket.sendText(message)
        println(message)
      }
    }
  } finally {
    consumer.close()
    actorSystem.terminate()
  }
}
