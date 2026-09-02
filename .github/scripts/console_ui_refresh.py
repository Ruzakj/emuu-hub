from pathlib import Path

p=Path('app/src/main/java/com/ric/emuhub/MainActivity.kt')
s=p.read_text()
start=s.index('    private fun renderHome(){')
end=s.index('    private fun sectionTitle', start)
new='''    private fun renderHome(){
        window.statusBarColor=0xFF030406.toInt();window.navigationBarColor=0xFF030406.toInt()
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(0xFF030406.toInt())}
        val scroll=ScrollView(this).apply{isFillViewport=true;overScrollMode=View.OVER_SCROLL_NEVER}
        val content=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(10),dp(16),dp(32))}
        val header=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        val brand=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        brand.addView(textView("EMU HUB",25f,0xFFFFFFFF.toInt(),true).apply{letterSpacing=0.05f})
        brand.addView(textView("CONSOLE HOME",9f,0xFF788291.toInt(),true).apply{letterSpacing=0.19f},LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{topMargin=dp(2)})
        header.addView(brand,LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
        header.addView(textView("● ONLINE",9f,0xFF8FF0B5.toInt(),true).apply{gravity=Gravity.CENTER;background=rounded(0xFF0A1610.toInt(),15,0xFF244D35.toInt());setPadding(dp(11),dp(7),dp(11),dp(7))})
        content.addView(header)

        val hero=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(19),dp(18),dp(19),dp(18));background=rounded(0xFF101722.toInt(),27,0xFF26354A.toInt())}
        content.addView(hero,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{topMargin=dp(15)})
        val heroTop=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        heroTop.addView(textView("READY TO PLAY",9.5f,0xFF8EA2BE.toInt(),true).apply{letterSpacing=0.16f},LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
        countBadge=textView("0 GAMES",9.5f,0xFFFFFFFF.toInt(),true).apply{gravity=Gravity.CENTER;background=rounded(0xFF1B2635.toInt(),13,0xFF344861.toInt());setPadding(dp(10),dp(6),dp(10),dp(6))}
        heroTop.addView(countBadge);hero.addView(heroTop)
        hero.addView(textView("Your games. One console.",24f,0xFFFFFFFF.toInt(),true),LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{topMargin=dp(10)})
        status=textView("Select a title — Emu Hub routes the right engine automatically",10.5f,0xFF91A0B3.toInt())
        hero.addView(status,LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{topMargin=dp(5)})
        val heroActions=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        heroActions.addView(consoleAction("＋","ADD GAMES") { chooseRomFolder() },LinearLayout.LayoutParams(0,dp(46),1f).apply{rightMargin=dp(5)})
        heroActions.addView(consoleAction("▶","PLAY FILE") { openRomPicker() },LinearLayout.LayoutParams(0,dp(46),1f).apply{leftMargin=dp(5)})
        hero.addView(heroActions,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(46)).apply{topMargin=dp(16)})

        val nav=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(5),dp(5),dp(5),dp(5));background=rounded(0xFF090D13.toInt(),20,0xFF1D2632.toInt())}
        nav.addView(consoleNav("↻","SCAN","Library") { refreshAllFolders(true) },LinearLayout.LayoutParams(0,dp(64),1f))
        nav.addView(consoleNav("⚙","PS2","Tuning") { startActivity(Intent(this@MainActivity,Ps2SettingsActivity::class.java)) },LinearLayout.LayoutParams(0,dp(64),1f))
        nav.addView(consoleNav("⇩","UPDATE","System") { startActivity(Intent(this@MainActivity,UpdateActivity::class.java)) },LinearLayout.LayoutParams(0,dp(64),1f))
        content.addView(nav,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(74)).apply{topMargin=dp(11)})

        val consoleHead=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        consoleHead.addView(sectionTitle("CONSOLES"),LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f));consoleHead.addView(textView("CHOOSE SYSTEM",8.5f,0xFF586474.toInt(),true))
        content.addView(consoleHead,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{topMargin=dp(22)})
        content.addView(buildConsoleStrip(),LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(94)).apply{topMargin=dp(10)})

        val libraryHeader=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        libraryHeader.addView(sectionTitle("GAME SHELF"),LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f));libraryHeader.addView(textView("RECENT + ALL",8.5f,0xFF667385.toInt(),true))
        content.addView(libraryHeader,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{topMargin=dp(22)})
        library=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(0,dp(10),0,0)};content.addView(library)
        scroll.addView(content,ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));root.addView(scroll,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f));setContentView(root)
    }

    private fun consoleAction(icon:String,title:String,onClick:()->Unit)=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER;setPadding(dp(12),0,dp(12),0);background=rounded(0xFFF3F6FA.toInt(),15);isClickable=true;isFocusable=true;addView(textView(icon,15f,0xFF05070A.toInt(),true));addView(textView(title,10f,0xFF05070A.toInt(),true),LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{leftMargin=dp(7)});setOnClickListener{onClick()}}

    private fun consoleNav(icon:String,title:String,subtitle:String,onClick:()->Unit)=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;isClickable=true;isFocusable=true;addView(textView(icon,17f,0xFFF6F8FB.toInt(),true).apply{gravity=Gravity.CENTER});addView(textView(title,9.5f,0xFFF5F7FA.toInt(),true),LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{topMargin=dp(3)});addView(textView(subtitle,8f,0xFF697687.toInt()),LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{topMargin=dp(1)});setOnClickListener{onClick()}}

'''
s=s[:start]+new+s[end:]
p.write_text(s)
