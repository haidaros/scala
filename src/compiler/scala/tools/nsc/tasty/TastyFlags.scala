package scala.tools.nsc.tasty

/**Flags from TASTy with no equivalent in scalac
 */
object TastyFlags {

  final val EmptyTastyFlags: TastyFlagSet = TastyFlagSet(0)
  final val Erased: TastyFlagSet          = EmptyTastyFlags.next
  final val Internal: TastyFlagSet        = Erased.next
  final val Inline: TastyFlagSet          = Internal.next
  final val InlineProxy: TastyFlagSet     = Inline.next
  final val Opaque: TastyFlagSet          = InlineProxy.next
  final val Scala2x: TastyFlagSet         = Opaque.next
  final val Extension: TastyFlagSet       = Scala2x.next
  final val Given: TastyFlagSet           = Extension.next
  final val Exported: TastyFlagSet        = Given.next
  final val NoInits: TastyFlagSet         = Exported.next
  final val TastyMacro: TastyFlagSet      = NoInits.next
  final val Enum: TastyFlagSet            = TastyMacro.next
  final val Open: TastyFlagSet            = Enum.next
  final val maxFlag: Int                  = Open.shift

  case class TastyFlagSet private[TastyFlags](private val flags: Int) extends AnyVal {

    private[TastyFlags] def shift: Int = {
      var acc = 0
      var curr = flags
      while (curr != 0) {
        acc += 1
        curr = curr >> 1
      }
      acc
    }

    private[TastyFlags] def next: TastyFlagSet = {
      TastyFlagSet(1 << shift + 1)
    }

    def toSingletonSets: SingletonSets                        = SingletonSets(flags)
    def |(other: TastyFlagSet): TastyFlagSet                  = TastyFlagSet(flags | other.flags)
    def &(mask: TastyFlagSet): TastyFlagSet                   = TastyFlagSet(flags & mask.flags)
    def &~(mask: TastyFlagSet): TastyFlagSet                  = TastyFlagSet(flags & ~mask.flags)
    def unary_! : Boolean                                     = this.flags == 0
    def is(mask: TastyFlagSet): Boolean                       = (this & mask) == mask
    def isOneOf(mask: TastyFlagSet): Boolean                  = (this & mask).hasFlags
    def is(mask: TastyFlagSet, butNot: TastyFlagSet): Boolean = if (!butNot) is(mask) else is(mask) && not(butNot)
    def not(mask: TastyFlagSet): Boolean                      = !isOneOf(mask)
    def hasFlags: Boolean                                     = this.flags != 0
    def except(mask: TastyFlagSet): (Boolean, TastyFlagSet)   = is(mask) -> (this &~ mask)

    def debug: String = {
      if (!this) {
        "EmptyTastyFlags"
      }
      else {
        toSingletonSets.map { f =>
          (f: @unchecked) match {
            case Erased      => "Erased"
            case Internal    => "Internal"
            case Inline      => "Inline"
            case InlineProxy => "InlineProxy"
            case Opaque      => "Opaque"
            case Scala2x     => "Scala2x"
            case Extension   => "Extension"
            case Given       => "Given"
            case Exported    => "Exported"
            case NoInits     => "NoInits"
            case TastyMacro  => "TastyMacro"
            case Enum        => "Enum"
            case Open        => "Open"
          }
        } mkString(" | ")
      }
    }
  }

  case class SingletonSets private[TastyFlags](private val set: Int) extends AnyVal {
    def map[A](f: TastyFlagSet => A): Iterable[A] = {
      val buf = Iterable.newBuilder[A]
      var i = 0
      while (i <= maxFlag) {
        val flag = 1 << i
        if ((flag & set) != 0) {
          buf += f(TastyFlagSet(flag))
        }
        i += 1
      }
      buf.result
    }
  }

}
