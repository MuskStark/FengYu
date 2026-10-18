import { EncryptedText } from '@/aceternity/encrypted-text'
import { cn } from '@/lib/utils'
import { AcnScope } from './scope'

/**
 * Decode-style reveal for the zh hero line. The vendored default charset is
 * ASCII-only, which would make Chinese flash latin gibberish — this charset
 * adds common CJK characters and full-width punctuation so zh scrambles as
 * naturally as en (ported from the app's CipherText).
 */
const CHARSET =
  'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*()_+-=[]{};:,.<>?~' +
  '，。、；：？！（）【】《》“”‘’…—·' +
  '的一是在不了有和人这中大为上个国我以要他时来用们生到作地于出就分对成会可主发年动同工也能下过子说产种面而方后多定行学法所民得经十三之进着等部度家电力里如水化高自二理起小物现实加量都两体制机当使点从业本去把性好应开它合还因由其些然前外天政四日那社义事平形相全表间样与关各重新线内数正心反你明看原又么利比或但质气第向道命此变条只没结解问意建月公无系很情者最立代想已通并提直题展五果象员入常文总次品式活设管特件长求老基资边流路级少图统接知较将组见计别她手角期根论运指几强放决被做必战先回则任取据处给色光门即保造百规热领七海口东导器压志世金增争济术极交受联什认六共权收证改清美再采转更单风切打白教速花带安场身车例真务具万每目至达走积示议声报斗完类八离华名确才科张信马节话米整空元况今集温传土许步群广石记需段研界拉林律叫且究观越织装影算低持音众书布复容儿须际商非验连断深难近矿千周素技备半办青省列习响约支般史感劳便团往历市克何除消构府称太准精值号率族维划选标写存候快效院查江型眼王按格养易置派层片始却专状育识适属圆包火住调满县局照参红细引听价严首底液官德随病失讲配女黄推显谈神艺呢席含企望密批营项防举球英氧势告台落木帮轮破亚师围注远字材排供河态封另施减树溶怎止案言士均武固叶鱼波视仅费紧爱左章早朝害续轻服试食充兵源判护司足某练差致板田降黑彼率刻夜课皇误础察绝富座争硬盘客兴趣除背蜂语流程插件技能智能文档平台'

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
    <AcnScope as="span" variant="cipher" className="inline">
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
