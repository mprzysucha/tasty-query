package tastyquery.reader

import tastyquery.Contexts.*
import tastyquery.ast.Names.{nme, *}
import tastyquery.ast.{ParamSig, Signature, TermSig, TypeLenSig}
import tastyquery.reader.TreeUnpickler
import tastyquery.unsafe

import dotty.tools.tasty.{TastyBuffer, TastyFormat, TastyHeaderUnpickler, TastyReader}
import TastyBuffer.{Addr, NameRef}
import TastyFormat.NameTags

import scala.collection.mutable

object TastyUnpickler {

  abstract class SectionUnpickler[R](val name: String) {
    def unpickle(reader: TastyReader, nameAtRef: NameTable)(using Context): R
  }

  class TreeSectionUnpickler(posUnpickler: Option[PositionUnpickler]) extends SectionUnpickler[TreeUnpickler]("ASTs") {
    def unpickle(reader: TastyReader, nameAtRef: NameTable)(using Context): TreeUnpickler =
      new TreeUnpickler(reader, nameAtRef, posUnpickler)
  }

  class PositionSectionUnpickler extends SectionUnpickler[PositionUnpickler]("Positions") {
    def unpickle(reader: TastyReader, nameAtRef: NameTable)(using Context): PositionUnpickler =
      new PositionUnpickler(reader, nameAtRef)
  }

  final class NameTable {
    private[TastyUnpickler] type EitherName = TermName | FullyQualifiedName

    private val names = new mutable.ArrayBuffer[EitherName]

    private[TastyUnpickler] def add(name: EitherName): Unit = names += name

    private[TastyUnpickler] def apply(ref: NameRef): EitherName =
      names(ref.index)

    def simple(ref: NameRef): TermName =
      apply(ref) match
        case name: TermName =>
          name
        case name: FullyQualifiedName =>
          throw IllegalArgumentException(s"Expected TermName but got ${name.toDebugString}")

    def fullyQualified(ref: NameRef): FullyQualifiedName =
      apply(ref) match
        case name: FullyQualifiedName =>
          name
        case name: TermName =>
          FullyQualifiedName(name :: Nil)
  }

}

import tastyquery.reader.TastyUnpickler.*

class TastyUnpickler(reader: TastyReader) {

  import reader.*

  def this(bytes: IArray[Byte]) =
    // ok to use as Array because TastyReader is readOnly
    this(new TastyReader(unsafe.asByteArray(bytes)))

  private val sectionReader = new mutable.HashMap[String, TastyReader]
  val nameAtRef: NameTable = new NameTable

  private def readName(): TermName = nameAtRef.simple(readNameRef())

  private def readFullyQualifiedName(): FullyQualifiedName = nameAtRef.fullyQualified(readNameRef())

  private def readEitherName(): nameAtRef.EitherName = nameAtRef(readNameRef())

  private def readString(): String = readName().toString

  private def readParamSig(): ParamSig = {
    val ref = readInt()
    if (ref < 0)
      TypeLenSig(ref.abs)
    else
      TermSig(nameAtRef.fullyQualified(new NameRef(ref)).mapLast(_.toTypeName))
  }

  private def readNameContents(): nameAtRef.EitherName = {
    val tag = readByte()
    val length = readNat()
    val start: Addr = reader.currentAddr
    val end: Addr = start + length
    val result: nameAtRef.EitherName = tag match {
      case NameTags.UTF8 =>
        reader.goto(end)
        termName(bytes, start.index, length)
      case NameTags.QUALIFIED =>
        val qual = readFullyQualifiedName()
        val item = readName()
        FullyQualifiedName(qual.path :+ item)
      case NameTags.EXPANDED | NameTags.EXPANDPREFIX =>
        new ExpandedName(tag, readName(), readName().asSimpleName)
      case NameTags.UNIQUE =>
        val separator = readName().toString
        val num = readNat()
        val originals = reader.until(end)(readName())
        val original = if (originals.isEmpty) nme.EmptyTermName else originals.head
        new UniqueName(separator, original, num)
      case NameTags.DEFAULTGETTER =>
        new DefaultGetterName(readName(), readNat())
      case NameTags.SIGNED | NameTags.TARGETSIGNED =>
        val original = readName()
        val target = if (tag == NameTags.TARGETSIGNED) readName() else original
        val result = readFullyQualifiedName().mapLast(_.toTypeName)
        val paramsSig = reader.until(end)(readParamSig())
        val sig = Signature(paramsSig, result)
        new SignedName(original, sig, target)
      case NameTags.SUPERACCESSOR | NameTags.INLINEACCESSOR =>
        new PrefixedName(tag, readName())
      case NameTags.BODYRETAINER =>
        new SuffixedName(tag, readName())
      case NameTags.OBJECTCLASS =>
        readEitherName() match
          case simple: TermName              => simple.withObjectSuffix
          case qualified: FullyQualifiedName => qualified.mapLast(_.asSimpleName.withObjectSuffix)
      case _ => throw new UnsupportedOperationException(s"unexpected tag: $tag")
    }
    assert(reader.currentAddr == end, s"bad name $result $start ${reader.currentAddr} $end")
    result
  }

  new TastyHeaderUnpickler(reader).readHeader()

  locally {
    reader.until(readEnd())(nameAtRef.add(readNameContents()))
    while (!isAtEnd) {
      val secName = readString()
      val secEnd: Addr = readEnd()
      sectionReader(secName) = new TastyReader(bytes, currentAddr.index, secEnd.index, currentAddr.index)
      reader.goto(secEnd)
    }
  }

  def unpickle[R](sec: SectionUnpickler[R])(using Context): Option[R] =
    for (reader <- sectionReader.get(sec.name)) yield sec.unpickle(reader, nameAtRef)

  def bytes: Array[Byte] = reader.bytes
}
