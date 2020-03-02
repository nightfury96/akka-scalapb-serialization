package im.actor.serialization

import akka.serialization._
import com.github.benmanes.caffeine.cache.Caffeine
import com.google.protobuf.{ByteString, GeneratedMessage => GGeneratedMessage}
import scalapb.GeneratedMessage

import scala.util.{Failure, Success}

object ActorSerializer {
  private val ARRAY_OF_BYTE_ARRAY = Array[Class[_]](classOf[Array[Byte]])


  // FIXME: dynamically increase capacity
  private val map = Caffeine.newBuilder().build[Integer, Class[_]]()
  private val reverseMap = Caffeine.newBuilder().build[Class[_], Integer]()

  def clean(): Unit = {
    map.cleanUp()
    reverseMap.cleanUp()
  }

  def register(id: Int, clazz: Class[_]): Unit = {
    get(id) match {
      case None ⇒
        get(clazz) match {
          case Some(regId) ⇒ throw new IllegalArgumentException(s"There is already a mapping for class: $clazz, id: $regId")
          case None ⇒
            map.put(id, Class.forName(clazz.getName + '$'))
            reverseMap.put(clazz, id)
        }
      case Some(registered) ⇒
        if (!get(clazz).contains(id))
          throw new IllegalArgumentException(s"There is already a mapping with id $id: ${map.getIfPresent(id)}")
    }
  }

  def register(items: (Int, Class[_])*): Unit =
    items foreach { case (id, clazz) ⇒ register(id, clazz) }

  def get(id: Int): Option[Class[_]] = Option(map.getIfPresent(id))

  def get(clazz: Class[_]) = Option(reverseMap.getIfPresent(clazz))

  def fromMessage(message: SerializedMessage): AnyRef = {
    ActorSerializer.get(message.id) match {
      case Some(clazz) ⇒
        val field = clazz.getField("MODULE$").get(null)

        clazz
          .getDeclaredMethod("validate", ARRAY_OF_BYTE_ARRAY: _*)
          .invoke(field, message.bytes.toByteArray) match {
          case Success(msg) ⇒ msg.asInstanceOf[GeneratedMessage].asInstanceOf[AnyRef]
          case Failure(e) ⇒ throw e
        }
      case None ⇒ throw new IllegalArgumentException(s"Can't find mapping for id: ${message.id}")
    }
  }

  def fromBinary(bytes: Array[Byte]): AnyRef = fromMessage(SerializedMessage.parseFrom(bytes))

  def toMessage(o: AnyRef): SerializedMessage = {
    ActorSerializer.get(o.getClass) match {
      case Some(id) ⇒
        o match {
          case m: GeneratedMessage ⇒ SerializedMessage(id, ByteString.copyFrom(m.toByteArray))
          case m: GGeneratedMessage ⇒ SerializedMessage(id, ByteString.copyFrom(m.toByteArray))
          case _ ⇒ throw new IllegalArgumentException(s"Can't serialize non-scalapb message [${o}]")
        }
      case None ⇒
        throw new IllegalArgumentException(s"Can't find mapping for message [${o}]")
    }
  }

  def toBinary(o: AnyRef): Array[Byte] = toMessage(o).toByteArray
}

class ActorSerializerObsolete extends Serializer {

  override def identifier: Int = 3456

  override def includeManifest: Boolean = false

  override def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]): AnyRef = ActorSerializer.fromBinary(bytes)

  override def toBinary(o: AnyRef): Array[Byte] = ActorSerializer.toBinary(o)
}

class ActorSerializer extends SerializerWithStringManifest {

  override def identifier: Int = 3457

  override def manifest(o: AnyRef): String =
    ActorSerializer
      .get(o.getClass)
      .map(_.toString)
      .getOrElse(throw new IllegalArgumentException(s"Class ${o.getClass} is not registered"))

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef =
    ActorSerializer.fromMessage(SerializedMessage(manifest.toInt, ByteString.copyFrom(bytes)))

  override def toBinary(o: AnyRef): Array[Byte] =
    o match {
      case g: GeneratedMessage => g.toByteArray
      case _ => throw new IllegalArgumentException("Only GeneratedMessage is supported")
    }
}
