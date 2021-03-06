package examples.hybrid.blocks

import com.google.common.primitives.{Bytes, Ints, Longs}
import examples.commons.{PublicKey25519NoncedBox, PublicKey25519NoncedBoxSerializer, SimpleBoxTransaction, SimpleBoxTransactionCompanion}
import io.circe.Encoder
import io.circe.syntax._
import scorex.core.block.Block
import scorex.core.block.Block._
import scorex.core.serialization.Serializer
import scorex.core.transaction.proof.Signature25519
import scorex.core.transaction.state.PrivateKey25519
import scorex.core.utils.ScorexEncoding
import scorex.core.{ModifierTypeId, TransactionsCarryingPersistentNodeViewModifier, idToBytes}
import scorex.crypto.hash.Blake2b256
import scorex.crypto.signatures.{Curve25519, Signature}
import scorex.util.{ModifierId, ScorexLogging, bytesToId}

import scala.util.Try

case class PosBlock(override val parentId: BlockId, //PoW block
                    override val timestamp: Block.Timestamp,
                    override val transactions: Seq[SimpleBoxTransaction],
                    generatorBox: PublicKey25519NoncedBox,
                    attachment: Array[Byte],
                    signature: Signature25519
                   ) extends HybridBlock
  with TransactionsCarryingPersistentNodeViewModifier[SimpleBoxTransaction] with ScorexLogging {
  override type M = PosBlock

  override lazy val serializer = PosBlockCompanion

  override lazy val version: Version = 0: Byte

  override lazy val modifierTypeId: ModifierTypeId = PosBlock.ModifierTypeId

  override lazy val id: ModifierId =
    bytesToId(Blake2b256(idToBytes(parentId) ++ Longs.toByteArray(timestamp) ++ generatorBox.id ++ attachment))

  override def toString: String = s"PoSBlock(${this.asJson.noSpaces})"
}

object PosBlockCompanion extends Serializer[PosBlock] with ScorexEncoding {
  override def toBytes(b: PosBlock): Array[Byte] = {
    val txsBytes = b.transactions.sortBy(t => encoder.encodeId(t.id)).foldLeft(Array[Byte]()) { (a, b) =>
      Bytes.concat(Ints.toByteArray(b.bytes.length), b.bytes, a)
    }
    Bytes.concat(idToBytes(b.parentId), Longs.toByteArray(b.timestamp), b.generatorBox.bytes, b.signature.bytes,
      Ints.toByteArray(b.transactions.length), txsBytes, Ints.toByteArray(b.attachment.length), b.attachment)
  }

  override def parseBytes(bytes: Array[Byte]): Try[PosBlock] = Try {
    require(bytes.length <= PosBlock.MaxBlockSize)

    val parentId = bytesToId(bytes.slice(0, BlockIdLength))
    var position = BlockIdLength
    val timestamp = Longs.fromByteArray(bytes.slice(position, position + 8))
    position = position + 8

    val boxBytes = bytes.slice(position, position + PublicKey25519NoncedBox.BoxLength)
    val box = PublicKey25519NoncedBoxSerializer.parseBytes(boxBytes).get
    position = position + PublicKey25519NoncedBox.BoxLength

    val signature = Signature25519(Signature @@ bytes.slice(position, position + Signature25519.SignatureSize))
    position = position + Signature25519.SignatureSize

    val txsLength = Ints.fromByteArray(bytes.slice(position, position + 4))
    position = position + 4
    val txs: Seq[SimpleBoxTransaction] = (0 until txsLength) map { _ =>
      val l = Ints.fromByteArray(bytes.slice(position, position + 4))
      val tx = SimpleBoxTransactionCompanion.parseBytes(bytes.slice(position + 4, position + 4 + l)).get
      position = position + 4 + l
      tx
    }

    val attachmentLength = Ints.fromByteArray(bytes.slice(position, position + 4))
    val attachment = bytes.slice(position + 4, position + 4 + attachmentLength)
    PosBlock(parentId, timestamp, txs, box, attachment, signature)
  }
}

object PosBlock extends ScorexEncoding {
  val MaxBlockSize = 512 * 1024 //512K
  val ModifierTypeId: ModifierTypeId = scorex.core.ModifierTypeId @@ 4.toByte

  implicit val posBlockEncoder: Encoder[PosBlock] = (psb: PosBlock) => {
    Map(
      "id" -> encoder.encodeId(psb.id).asJson,
      "parentId" -> encoder.encodeId(psb.parentId).asJson,
      "attachment" -> encoder.encode(psb.attachment).asJson,
      "timestamp" -> psb.timestamp.asJson,
      "transactions" -> psb.transactions.map(_.asJson).asJson,
      "generatorBox" -> psb.generatorBox.asJson,
      "signature" -> encoder.encode(psb.signature.bytes).asJson
    ).asJson
  }

  def create(parentId: BlockId,
             timestamp: Block.Timestamp,
             txs: Seq[SimpleBoxTransaction],
             box: PublicKey25519NoncedBox,
             attachment: Array[Byte],
             privateKey: PrivateKey25519): PosBlock = {
    require(java.util.Arrays.equals(box.proposition.pubKeyBytes, privateKey.publicKeyBytes))
    val unsigned = PosBlock(parentId, timestamp, txs, box, attachment, Signature25519(Signature @@ Array[Byte]()))
    val signature = Curve25519.sign(privateKey.privKeyBytes, unsigned.bytes)
    unsigned.copy(signature = Signature25519(signature))
  }

  def signatureValid(posBlock: PosBlock): Boolean = {
    val unsignedBytes = posBlock.copy(signature = Signature25519(Signature @@ Array[Byte]())).bytes
    posBlock.generatorBox.proposition.verify(unsignedBytes, posBlock.signature.signature)
  }
}
