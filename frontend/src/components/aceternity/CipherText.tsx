import { EncryptedText } from '@/aceternity/encrypted-text'
import { cn } from '@/lib/utils'
import { AcnScope } from './scope'

/**
 * Decode-style reveal for quiet title moments (empty states, placeholders).
 * The vendored default charset is ASCII-only, which would make Chinese titles
 * flash latin gibberish — this charset adds common CJK characters and
 * full-width punctuation so zh scrambles as naturally as en.
 */
const CHARSET =
  'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*()_+-=[]{};:,.<>?~' +
  '，。、；：？！（）【】《》“”‘’…—·' +
  '的一是在不了有和人这中大为上个国我以要他时来用们生到作地于出就分对成会可主发年动同工也能下过子说产种面而方后多定行学法所民得经十三之进着等部度家电力里如水化高自二理起小物现实加量都两体制机当使点从业本去把性好应开它合还因由其些然前外天政四日那社义事平形相全表间样与关各重新线内数正心反你明看原又么利比或但质气第向道命此变条只没结解问意建月公无系军很情者最立代想已通并提直题党程展五果料象员革位入常文总次品式活设及管特件长求老头基资边流路级少图山统接知较将组见计别她手角期根论运农指几九区强放决西被干做必战先回则任取据处队南给色光门即保治北造百规热领七海口东导器压志世金增争济阶油思术极交受联什认六共权收证改清美再采转更单风切打白教速花带安场身车例真务具万每目至达走积示议声报斗完类八离华名确才科张信马节话米整空元况今集温传土许步群广石记需段研界拉林律叫且究观越织装影算低持音众书布复容儿须际商非验连断深难近矿千周委素技备半办青省列习响约支般史感劳便团往酸历市克何除消构府称太准精值号率族维划选标写存候毛亲快效斯院查江型眼王按格养易置派层片始却专状育厂京识适属圆包火住调满县局照参红细引听该铁价严首底液官德随病苏失尔死讲配女黄推显谈罪神艺呢席含企望密批营项防举球英氧势告李台落木帮轮破亚师围注远字材排供河态封另施减树溶怎止案言士均武固叶鱼波视仅费紧爱左章早朝害续轻服试食充兵源判护司足某练差致板田降黑彼率刻夜课皇误紧础察绝富座争硬盘客兴习趣除背'

/**
 * Reveals `text` character by character through a scramble. Ink follows the
 * token palette: unrevealed glyphs sit at subtlest ink, revealed ones at full
 * foreground.
 */
export function CipherText({
  text,
  className,
  revealDelayMs = 40,
}: {
  text: string
  className?: string
  revealDelayMs?: number
}) {
  return (
    <AcnScope as="span" className="inline">
      <EncryptedText
        text={text}
        revealDelayMs={revealDelayMs}
        charset={CHARSET}
        className={cn('text-foreground', className)}
        encryptedClassName="text-foreground-subtlest"
        revealedClassName="text-foreground"
      />
    </AcnScope>
  )
}
