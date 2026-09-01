from pathlib import Path
import re

p = Path('app/src/main/java/com/ric/emuhub/MainActivity.kt')
s = p.read_text()

# Imports for local cover art support.
if 'import android.graphics.BitmapFactory' not in s:
    s = s.replace('import android.graphics.Typeface\n', 'import android.graphics.Typeface\nimport android.graphics.BitmapFactory\n')
if 'import android.widget.ImageView' not in s:
    s = s.replace('import android.widget.FrameLayout\n', 'import android.widget.FrameLayout\nimport android.widget.ImageView\n')

# Recent-played persistence.
if 'KEY_RECENT_PLAYED' not in s:
    s = s.replace('        private const val KEY_PSP_RESOLUTION = "psp_resolution"\n', '        private const val KEY_PSP_RESOLUTION = "psp_resolution"\n        private const val KEY_RECENT_PLAYED = "recent_played_v1"\n')

# Refresh modern library on resume so Recently Played updates after returning from a game.
old_resume = '''        if (::status.isInitialized) {
            val bios = Ps2BiosActivity.selectedBios(this)
            if (bios != null && status.text.toString().startsWith("PS2 BIOS")) status.text = "PS2 BIOS ready • ${bios.name}"
        }
'''
new_resume = '''        if (::status.isInitialized) {
            val bios = Ps2BiosActivity.selectedBios(this)
            if (bios != null && status.text.toString().startsWith("PS2 BIOS")) status.text = "PS2 BIOS ready • ${bios.name}"
        }
        if (::library.isInitialized && allLibraryGames.isNotEmpty()) {
            renderLibrary(allLibraryGames, "${allLibraryGames.size} game")
        }
'''
if old_resume in s:
    s = s.replace(old_resume, new_resume, 1)

# Replace list-oriented library with modern recent shelf + filters + 2-column cover grid.
pattern = re.compile(r'    private fun renderLibrary\(games:List<GameEntry>,label:String\)\{.*?(?=    private fun systemCode\(e:String\))', re.S)
replacement = r'''    private fun renderLibrary(games:List<GameEntry>,label:String){
        allLibraryGames=games
        library.removeAllViews()
        val visible=activeConsoleFilter?.let{f->games.filter{inferredConsole(it)==f}}?:games
        countBadge.text="${visible.size} GAMES"
        status.text=if(activeConsoleFilter==null)"$label • tap a game and Emu Hub picks the engine" else "${activeConsoleFilter} • ${visible.size} game"

        val recent=loadRecentlyPlayed().mapNotNull{r->games.firstOrNull{it.uri==r.uri}?:r}.distinctBy{it.uri}.take(10)
        if(recent.isNotEmpty()){
            val recentHeader=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
            recentHeader.addView(textView("RECENTLY PLAYED",10.5f,0xFFB9C3D1.toInt(),true).apply{letterSpacing=0.12f},LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
            recentHeader.addView(textView("CONTINUE",9f,0xFF697586.toInt(),true))
            library.addView(recentHeader,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{bottomMargin=dp(9)})
            val hsv=HorizontalScrollView(this).apply{isHorizontalScrollBarEnabled=false;overScrollMode=View.OVER_SCROLL_NEVER;clipToPadding=false}
            val shelf=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
            recent.forEachIndexed{i,g->shelf.addView(buildRecentCard(g),LinearLayout.LayoutParams(dp(128),dp(174)).apply{if(i>0)leftMargin=dp(9)})}
            hsv.addView(shelf,ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,dp(174)))
            library.addView(hsv,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(174)))
        }

        val filtersHeader=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        filtersHeader.addView(textView("GAME LIBRARY",10.5f,0xFFB9C3D1.toInt(),true).apply{letterSpacing=0.12f},LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
        filtersHeader.addView(textView("${games.size} TOTAL",9f,0xFF697586.toInt(),true))
        library.addView(filtersHeader,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{topMargin=if(recent.isNotEmpty())dp(22) else 0;bottomMargin=dp(9)})
        library.addView(buildFilterStrip(),LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(42)).apply{bottomMargin=dp(12)})

        if(visible.isEmpty()){
            val empty=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setPadding(dp(20),dp(26),dp(20),dp(26));background=rounded(0xFF0D1117.toInt(),20,0xFF202630.toInt())}
            empty.addView(textView("No ${activeConsoleFilter?:""} games",16f,0xFFF6F7F9.toInt(),true))
            empty.addView(textView("Choose ALL or another console filter.",10.5f,0xFF747E8D.toInt()),LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{topMargin=dp(6)})
            library.addView(empty);return
        }

        val sorted=sortGames(visible)
        val grid=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        sorted.chunked(2).forEach{pair->
            val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.TOP}
            pair.forEachIndexed{idx,g->
                row.addView(buildGameTile(g),LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f).apply{
                    if(idx==0)rightMargin=dp(5) else leftMargin=dp(5)
                })
            }
            if(pair.size==1)row.addView(View(this),LinearLayout.LayoutParams(0,1,1f).apply{leftMargin=dp(5)})
            grid.addView(row,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{bottomMargin=dp(10)})
        }
        library.addView(grid)
    }

    private fun buildFilterStrip():View{
        val hsv=HorizontalScrollView(this).apply{isHorizontalScrollBarEnabled=false;overScrollMode=View.OVER_SCROLL_NEVER;clipToPadding=false}
        val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        val filters=listOf("ALL","PSP","PS1","PS2","GBA","NES","SNES","SWITCH","JAVA")
        filters.forEachIndexed{i,label->
            val selected=(label=="ALL"&&activeConsoleFilter==null)||activeConsoleFilter==label
            val chip=textView(label,9.5f,if(selected)0xFF071018.toInt() else 0xFFB2BCC9.toInt(),true).apply{
                gravity=Gravity.CENTER
                background=rounded(if(selected)0xFFE7F0FA.toInt() else 0xFF0D1219.toInt(),13,if(selected)0xFFE7F0FA.toInt() else 0xFF242C37.toInt())
                setPadding(dp(15),0,dp(15),0);isClickable=true;isFocusable=true
                setOnClickListener{
                    if(label=="JAVA")startActivity(Intent(this@MainActivity,J2meLibraryActivity::class.java))
                    else{activeConsoleFilter=if(label=="ALL")null else label;renderLibrary(allLibraryGames,"${allLibraryGames.size} game")}
                }
            }
            row.addView(chip,LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,dp(36)).apply{if(i>0)leftMargin=dp(7)})
        }
        hsv.addView(row,ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,dp(36)));return hsv
    }

    private fun localCoverFile(g:GameEntry):File?{
        val rom=directGameFile(Uri.parse(g.uri))?:return null
        val base=rom.nameWithoutExtension
        val candidates=listOf(
            File(rom.parentFile,"$base.jpg"),File(rom.parentFile,"$base.jpeg"),File(rom.parentFile,"$base.png"),
            File(rom.parentFile,"cover.jpg"),File(rom.parentFile,"cover.png"),File(rom.parentFile,"folder.jpg"),File(rom.parentFile,"folder.png")
        )
        return candidates.firstOrNull{it.isFile&&it.canRead()}
    }

    private fun coverView(g:GameEntry,height:Int):View{
        val frame=FrameLayout(this).apply{background=rounded(systemColorFor(g),18);clipToOutline=true}
        val cover=localCoverFile(g)
        if(cover!=null){
            val bmp=runCatching{BitmapFactory.decodeFile(cover.absolutePath)}.getOrNull()
            if(bmp!=null)frame.addView(ImageView(this).apply{setImageBitmap(bmp);scaleType=ImageView.ScaleType.CENTER_CROP},FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT))
        }
        if(frame.childCount==0){
            frame.addView(textView(systemCodeFor(g),10f,0xFFDCE7F2.toInt(),true).apply{gravity=Gravity.TOP or Gravity.START;setPadding(dp(12),dp(11),0,0);letterSpacing=0.10f},FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT))
            frame.addView(textView(g.name.substringBeforeLast('.',g.name).take(34),17f,0xFFFFFFFF.toInt(),true).apply{gravity=Gravity.BOTTOM or Gravity.START;setPadding(dp(12),0,dp(10),dp(14));maxLines=3},FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT))
        }
        val engine=textView(engineLabel(g),8.5f,0xFFFFFFFF.toInt(),true).apply{gravity=Gravity.CENTER;background=rounded(0x99000000.toInt(),10);setPadding(dp(8),dp(4),dp(8),dp(4))}
        frame.addView(engine,FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT,Gravity.TOP or Gravity.END).apply{topMargin=dp(9);rightMargin=dp(9)})
        return frame
    }

    private fun buildGameTile(g:GameEntry):View{
        return LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL;setPadding(dp(8),dp(8),dp(8),dp(10));background=rounded(0xFF0D1117.toInt(),20,0xFF202833.toInt());isClickable=true;isFocusable=true
            addView(coverView(g,dp(142)),LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(142)))
            addView(textView(g.name.substringBeforeLast('.',g.name),12f,0xFFF5F7FA.toInt(),true).apply{maxLines=2},LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{topMargin=dp(9)})
            val meta=LinearLayout(this@MainActivity).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
            meta.addView(textView(systemCodeFor(g),8.5f,0xFF8E9AAA.toInt(),true),LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
            meta.addView(textView("▶",10f,0xFFFFFFFF.toInt(),true))
            addView(meta,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{topMargin=dp(5)})
            setOnClickListener{openLibraryGame(g)}
        }
    }

    private fun buildRecentCard(g:GameEntry):View{
        return LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL;setPadding(dp(7),dp(7),dp(7),dp(9));background=rounded(0xFF0C1118.toInt(),18,0xFF202833.toInt());isClickable=true;isFocusable=true
            addView(coverView(g,dp(118)),LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(118)))
            addView(textView(g.name.substringBeforeLast('.',g.name),10.5f,0xFFF4F6F9.toInt(),true).apply{maxLines=2},LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{topMargin=dp(7)})
            setOnClickListener{openLibraryGame(g)}
        }
    }

    private fun engineLabel(g:GameEntry)=when(inferredConsole(g)){
        "PSP"->"PPSSPP";"PS1"->"PCSX";"PS2"->"ARMSX2";"GBA"->"mGBA";"NES"->"FCEUmm";"SNES"->"Snes9x";"SWITCH"->"EDEN";"DISC"->"AUTO";"ARCHIVE"->"AUTO";else->"CORE"
    }

    private fun rememberPlayed(g:GameEntry){
        val recent=loadRecentlyPlayed().toMutableList()
        recent.removeAll{it.uri==g.uri}
        recent.add(0,g)
        val arr=JSONArray()
        recent.take(12).forEach{r->arr.put(JSONObject().put("u",r.uri).put("n",r.name).put("e",r.ext).put("f",r.folder))}
        prefs.edit().putString(KEY_RECENT_PLAYED,arr.toString()).apply()
    }

    private fun loadRecentlyPlayed():List<GameEntry>{
        return runCatching{
            val arr=JSONArray(prefs.getString(KEY_RECENT_PLAYED,"[]"))
            buildList{for(i in 0 until arr.length()){val o=arr.getJSONObject(i);add(GameEntry(o.getString("u"),o.getString("n"),o.getString("e"),o.optString("f")))}}
        }.getOrDefault(emptyList())
    }

'''
ns, n = pattern.subn(replacement, s, count=1)
if n != 1:
    raise SystemExit(f'library block replacement failed: {n}')
s = ns

# Record the game before routing it, then launch directly with existing resolver.
s = s.replace('    private fun openLibraryGame(g:GameEntry){\n        val uri=Uri.parse(g.uri)\n', '    private fun openLibraryGame(g:GameEntry){\n        rememberPlayed(g)\n        val uri=Uri.parse(g.uri)\n', 1)

p.write_text(s)
