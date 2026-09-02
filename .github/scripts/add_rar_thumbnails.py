from pathlib import Path
import re

gp=Path('app/build.gradle.kts')
s=gp.read_text()
if 'com.github.junrar:junrar:' not in s:
    s=s.replace('    implementation("org.tukaani:xz:1.10")\n','    implementation("org.tukaani:xz:1.10")\n    implementation("com.github.junrar:junrar:7.5.5")\n')
gp.write_text(s)

p=Path('app/src/main/java/com/ric/emuhub/ArchiveHelper.kt')
s=p.read_text()
if 'import com.github.junrar.Archive' not in s:
    s=s.replace('import android.net.Uri\n','import android.net.Uri\nimport com.github.junrar.Archive\n')
s=s.replace('private val ROM_EXTENSIONS = setOf("gb","gbc","gba","nes","sfc","smc","bin","cue","chd","iso","cso","ecm")','private val ROM_EXTENSIONS = setOf("gb","gbc","gba","nes","sfc","smc","bin","cue","chd","iso","cso","ecm","xci","nsp","nro")')
s=s.replace('val ARCHIVE_EXTENSIONS = setOf("zip","7z","tar","tgz","gz","bz2","xz","tbz2","txz")','val ARCHIVE_EXTENSIONS = setOf("zip","7z","rar","tar","tgz","gz","bz2","xz","tbz2","txz")')
s=s.replace('archiveName.lowercase(Locale.US).endsWith(".7z") -> extract7z(source, extractRoot)\n                archiveName.lowercase(Locale.US).endsWith(".zip") -> extractZip(source, extractRoot)','archiveName.lowercase(Locale.US).endsWith(".7z") -> extract7z(source, extractRoot)\n                archiveName.lowercase(Locale.US).endsWith(".rar") -> extractRar(source, extractRoot)\n                archiveName.lowercase(Locale.US).endsWith(".zip") -> extractZip(source, extractRoot)')
if 'private fun extractRar(' not in s:
    marker='    private fun extractTar(source: File, root: File, archiveName: String): List<ExtractedRom> {'
    rar='''    private fun extractRar(source: File, root: File): List<ExtractedRom> {
        val result = mutableListOf<ExtractedRom>()
        val total = longArrayOf(0L)
        Archive(source).use { rar ->
            var seen = 0
            while (true) {
                val entry = rar.nextFileHeader() ?: break
                if (++seen > MAX_ENTRIES) error("Terlalu banyak file di dalam archive")
                if (entry.isDirectory) continue
                val entryName = entry.fileNameW.takeIf { it.isNotBlank() } ?: entry.fileNameString
                val ext = romExt(entryName)
                if (ext !in ROM_EXTENSIONS) continue
                val target = safeTarget(root, entryName) ?: continue
                target.parentFile?.mkdirs()
                FileOutputStream(target).buffered().use { output ->
                    rar.extractFile(entry, output)
                }
                total[0] += target.length()
                if (total[0] > MAX_TOTAL_BYTES) error("Archive terlalu besar")
                result += ExtractedRom(target, target.name, ext)
            }
        }
        return result
    }

'''
    s=s.replace(marker,rar+marker)
p.write_text(s)

p=Path('app/src/main/java/com/ric/emuhub/MainActivity.kt')
s=p.read_text()
pat=re.compile(r'    private fun localCoverFile\(g:GameEntry\):File\?\{.*?\n    \}\n\n    private fun coverView',re.S)
new='''    private fun localCoverFile(g:GameEntry):File?{
        val rom=directGameFile(Uri.parse(g.uri))?:return null
        val base=rom.nameWithoutExtension
        val parent=rom.parentFile?:return null
        val artDirs=listOf(parent,File(parent,"covers"),File(parent,"cover"),File(parent,"boxart"),File(parent,"boxarts"),File(parent,"art"),File(parent,"images"),File(parent,"thumbnails"))
        val names=listOf(base,"$base-front","$base-cover","cover","front","boxart","folder","thumbnail","thumb")
        val imageExts=listOf("jpg","jpeg","png","webp")
        for(dir in artDirs)for(n in names)for(e in imageExts){
            val f=File(dir,"$n.$e")
            if(f.isFile&&f.canRead())return f
        }
        return null
    }

    private fun consoleGlyph(g:GameEntry)=when(inferredConsole(g)){
        "PSP"->"P";"PS1"->"1";"PS2"->"2";"GBA"->"G";"NES"->"N";"SNES"->"S";"SWITCH"->"▰";"DISC"->"◎";"ARCHIVE"->"◆";else->"•"
    }

    private fun coverView'''
s,n=pat.subn(new,s,count=1)
if n!=1:
    raise SystemExit('localCoverFile block not found')
old='''        if(frame.childCount==0){
            frame.addView(textView(systemCodeFor(g),10f,0xFFDCE7F2.toInt(),true).apply{gravity=Gravity.TOP or Gravity.START;setPadding(dp(12),dp(11),0,0);letterSpacing=0.10f},FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT))
            frame.addView(textView(g.name.substringBeforeLast('.',g.name).take(34),17f,0xFFFFFFFF.toInt(),true).apply{gravity=Gravity.BOTTOM or Gravity.START;setPadding(dp(12),0,dp(10),dp(14));maxLines=3},FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT))
        }'''
new='''        if(frame.childCount==0){
            frame.addView(textView(consoleGlyph(g),34f,0x44FFFFFF,true).apply{gravity=Gravity.TOP or Gravity.END;setPadding(0,dp(8),dp(12),0)},FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT))
            frame.addView(textView(systemCodeFor(g),10f,0xFFDCE7F2.toInt(),true).apply{gravity=Gravity.TOP or Gravity.START;setPadding(dp(12),dp(11),0,0);letterSpacing=0.10f},FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT))
            frame.addView(textView(g.name.substringBeforeLast('.',g.name).take(42),17f,0xFFFFFFFF.toInt(),true).apply{gravity=Gravity.BOTTOM or Gravity.START;setPadding(dp(12),0,dp(10),dp(14));maxLines=3},FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT))
        }'''
if old not in s:
    raise SystemExit('cover fallback block not found')
s=s.replace(old,new,1)
p.write_text(s)
