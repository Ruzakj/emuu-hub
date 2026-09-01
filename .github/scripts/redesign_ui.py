from pathlib import Path
import re

p = Path('app/src/main/java/com/ric/emuhub/MainActivity.kt')
s = p.read_text()
replacement = r'''    private fun renderHome(){
        window.statusBarColor=0xFF05070A.toInt();window.navigationBarColor=0xFF05070A.toInt()
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(0xFF05070A.toInt())}
        val scroll=ScrollView(this).apply{isFillViewport=true;overScrollMode=View.OVER_SCROLL_NEVER}
        val content=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(12),dp(18),dp(30))}
        val header=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        val brand=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        brand.addView(textView("EMU HUB",24f,0xFFFFFFFF.toInt(),true).apply{letterSpacing=0.04f})
        brand.addView(textView("PLAY • ANY GENERATION",9.5f,0xFF737A86.toInt(),true).apply{letterSpacing=0.12f},LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{topMargin=dp(3)})
        header.addView(brand,LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
        header.addView(textView("● READY",9f,0xFFB7F7D1.toInt(),true).apply{gravity=Gravity.CENTER;background=rounded(0xFF0D1713.toInt(),13,0xFF1C3C2C.toInt());setPadding(dp(11),dp(7),dp(11),dp(7))});content.addView(header)

        val hero=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(17),dp(18),dp(17));background=rounded(0xFF10141B.toInt(),24,0xFF242B36.toInt())}
        content.addView(hero,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{topMargin=dp(16)})
        val heroTop=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        heroTop.addView(textView("LIBRARY",10f,0xFF8B95A5.toInt(),true).apply{letterSpacing=0.16f},LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
        countBadge=textView("0 GAMES",10f,0xFFFFFFFF.toInt(),true).apply{gravity=Gravity.CENTER;background=rounded(0xFF1B222D.toInt(),12,0xFF303947.toInt());setPadding(dp(10),dp(6),dp(10),dp(6))}
        heroTop.addView(countBadge);hero.addView(heroTop)
        hero.addView(textView("Your games. Instantly.",25f,0xFFFFFFFF.toInt(),true),LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{topMargin=dp(10)})
        status=textView("Offline engines ready • choose a console or game",11f,0xFF8E98A8.toInt())
        hero.addView(status,LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{topMargin=dp(5)})
        val heroActions=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        heroActions.addView(compactAction("＋","ADD GAMES") { chooseRomFolder() },LinearLayout.LayoutParams(0,dp(44),1f).apply{rightMargin=dp(5)})
        heroActions.addView(compactAction("▶","OPEN ROM") { openRomPicker() },LinearLayout.LayoutParams(0,dp(44),1f).apply{leftMargin=dp(5)})
        hero.addView(heroActions,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(44)).apply{topMargin=dp(15)})

        val quickRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        quickRow.addView(miniTile("↻","Refresh","Scan library") { refreshAllFolders(true) },LinearLayout.LayoutParams(0,dp(76),1f).apply{rightMargin=dp(5)})
        quickRow.addView(miniTile("PS2","Tuning","BIOS & settings") { startActivity(Intent(this@MainActivity,Ps2SettingsActivity::class.java)) },LinearLayout.LayoutParams(0,dp(76),1f).apply{leftMargin=dp(5)})
        content.addView(quickRow,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(76)).apply{topMargin=dp(12)})

        val consoleHead=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        consoleHead.addView(sectionTitle("CHOOSE A CONSOLE"),LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f));consoleHead.addView(textView("8 SYSTEMS",9f,0xFF596170.toInt(),true))
        content.addView(consoleHead,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{topMargin=dp(22)})
        content.addView(buildConsoleStrip(),LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(94)).apply{topMargin=dp(10)})

        val libraryHeader=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        libraryHeader.addView(sectionTitle("RECENT & LIBRARY"),LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f));libraryHeader.addView(textView("ALL GAMES",9f,0xFF697180.toInt(),true))
        content.addView(libraryHeader,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{topMargin=dp(21)})
        library=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(0,dp(10),0,0)};content.addView(library)
        scroll.addView(content,ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));root.addView(scroll,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f));setContentView(root)
    }

    private fun sectionTitle(label:String)=textView(label,10.5f,0xFF8D96A5.toInt(),true).apply{letterSpacing=0.14f}
    private fun compactAction(icon:String,title:String,onClick:()->Unit)=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER;setPadding(dp(12),0,dp(12),0);background=rounded(0xFFFFFFFF.toInt(),14);isClickable=true;isFocusable=true;addView(textView(icon,15f,0xFF080A0E.toInt(),true));addView(textView(title,10f,0xFF080A0E.toInt(),true),LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{leftMargin=dp(7)});setOnClickListener{onClick()}}
    private fun miniTile(icon:String,title:String,subtitle:String,onClick:()->Unit)=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(13),dp(10),dp(12),dp(10));background=rounded(0xFF0D1117.toInt(),18,0xFF202630.toInt());isClickable=true;isFocusable=true;val mark=textView(icon,14f,0xFFFFFFFF.toInt(),true).apply{gravity=Gravity.CENTER;background=rounded(0xFF171D26.toInt(),12,0xFF28313D.toInt())};addView(mark,LinearLayout.LayoutParams(dp(38),dp(38)).apply{rightMargin=dp(10)});val box=LinearLayout(this@MainActivity).apply{orientation=LinearLayout.VERTICAL;addView(textView(title,11f,0xFFF5F7FA.toInt(),true));addView(textView(subtitle,9f,0xFF707A89.toInt()),LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{topMargin=dp(2)})};addView(box,LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f));setOnClickListener{onClick()}}

    private fun buildConsoleStrip():View{
        val hsv=HorizontalScrollView(this).apply{isHorizontalScrollBarEnabled=false;overScrollMode=View.OVER_SCROLL_NEVER;clipToPadding=false};val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        val consoles=listOf(arrayOf("PSP","PPSSPP","P"),arrayOf("PS1","PCSX","1"),arrayOf("PS2","ARMSX2","2"),arrayOf("GBA","mGBA","G"),arrayOf("NES","FCEUmm","N"),arrayOf("SNES","Snes9x","S"),arrayOf("JAVA","JL-Mod","J"),arrayOf("SWITCH","Eden","▰"))
        consoles.forEachIndexed{index,item->val chip=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setPadding(dp(11),dp(8),dp(11),dp(8));background=rounded(if(index==0)0xFF151B24.toInt() else 0xFF0C1016.toInt(),19,if(index==0)0xFF364152.toInt() else 0xFF202630.toInt());isClickable=true;isFocusable=true;val icon=textView(item[2],15f,0xFFFFFFFF.toInt(),true).apply{gravity=Gravity.CENTER;background=rounded(if(index==0)0xFF253246.toInt() else 0xFF171C24.toInt(),13)};addView(icon,LinearLayout.LayoutParams(dp(36),dp(36)));addView(textView(item[0],11f,0xFFF7F8FA.toInt(),true),LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{topMargin=dp(5)});addView(textView(item[1],8.5f,0xFF6F7988.toInt()).apply{maxLines=1},LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{topMargin=dp(1)});when(item[0]){"PS2"->setOnClickListener{status.text=if(Ps2BiosActivity.selectedBios(this@MainActivity)!=null)"PS2 BIOS ready • tap to change" else "PS2 BIOS setup • choose BIOS";startActivity(Intent(this@MainActivity,Ps2BiosActivity::class.java))};"JAVA"->setOnClickListener{startActivity(Intent(this@MainActivity,J2meLibraryActivity::class.java))};"SWITCH"->setOnClickListener{Toast.makeText(this@MainActivity,"Open a Switch ROM to launch Eden.",Toast.LENGTH_SHORT).show()};else->setOnClickListener{status.text="${item[0]} • ${item[1]} ready"}}};row.addView(chip,LinearLayout.LayoutParams(dp(86),dp(90)).apply{if(index>0)leftMargin=dp(8)})}
        hsv.addView(row,ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,dp(90)));return hsv
    }

'''
pattern = re.compile(r'    private fun renderHome\(\)\{.*?(?=    private fun showEmptyLibrary\(\)\{)', re.S)
ns, n = pattern.subn(replacement, s, count=1)
if n != 1: raise SystemExit(f'UI block replacement failed: {n}')
ns = ns.replace('setPadding(dp(20),dp(30),dp(20),dp(30));background=rounded(0xFF070707.toInt(),20,0xFF1B1B1B.toInt())','setPadding(dp(20),dp(24),dp(20),dp(24));background=rounded(0xFF0D1117.toInt(),20,0xFF202630.toInt())')
ns = ns.replace('textView("⌁",30f,0xFF777777.toInt(),true)','textView("＋",24f,0xFF8D96A5.toInt(),true)')
ns = ns.replace('textView("Your library is empty",16f,0xFFF2F2F2.toInt(),true)','textView("Build your game shelf",16f,0xFFF6F7F9.toInt(),true)')
ns = ns.replace('textView("Add a folder and Emu Hub will organize supported games automatically.",11f,0xFF777777.toInt())','textView("Add one ROM folder. Emu Hub will sort everything by console automatically.",10.5f,0xFF747E8D.toInt())')
p.write_text(ns)
