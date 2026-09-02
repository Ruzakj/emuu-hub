from pathlib import Path

p = Path('app/src/main/java/com/ric/emuhub/MainActivity.kt')
s = p.read_text()

old = '''                    if(session.roms.size==1)launchExtractedRom(session,session.roms.first())
                    else AlertDialog.Builder(this).setTitle("Pilih game dari archive").setItems(session.roms.map{it.displayName}.toTypedArray()){_,which->launchExtractedRom(session,session.roms[which])}.setNegativeButton("Batal"){_,_->session.root.deleteRecursively()}.setOnCancelListener{session.root.deleteRecursively()}.show()'''
new = '''                    if(session.roms.size==1)launchExtractedRom(session,session.roms.first())
                    else {
                        val labels=session.roms.map{rom->"${archiveRomSystemLabel(rom)}  •  ${rom.displayName}"}.toTypedArray()
                        AlertDialog.Builder(this).setTitle("Pilih game dari compressed file").setItems(labels){_,which->launchExtractedRom(session,session.roms[which])}.setNegativeButton("Batal"){_,_->session.root.deleteRecursively()}.setOnCancelListener{session.root.deleteRecursively()}.show()
                    }'''
assert old in s, 'archive chooser target not found'
s = s.replace(old, new, 1)

marker = '    private fun launchExtractedRom(session:ArchiveHelper.Session,rom:ArchiveHelper.ExtractedRom){\n'
helper = '''    private fun archiveRomSystemLabel(rom:ArchiveHelper.ExtractedRom):String=when(rom.ext){
        "cso"->"PSP"
        "bin","cue","ecm"->"PS1"
        "gb","gbc","gba"->"GBA"
        "nes"->"NES"
        "sfc","smc"->"SNES"
        "xci","nsp","nro"->"SWITCH"
        "iso"->probeIsoTarget(Uri.fromFile(rom.file)) ?: "DISC"
        "chd"->"DISC"
        else->rom.ext.uppercase()
    }

'''
assert marker in s, 'launchExtractedRom marker not found'
s = s.replace(marker, helper + marker, 1)

old2 = '                    "PS2"->launchExtractedPs2OrSetup(session,rom)'
new2 = '''                    "PS2"->{
                        if(Ps2BiosActivity.selectedBios(this)!=null)launchExtractedPs2OrSetup(session,rom)
                        else showExtractedIsoChooser(session,rom)
                    }'''
assert old2 in s, 'PS2 extracted ISO route not found'
s = s.replace(old2, new2, 1)

old3 = '''        if(Ps2BiosActivity.selectedBios(this)==null){
            session.root.deleteRecursively()
            Toast.makeText(this,"Setup BIOS PS2 dulu dari kartu PS2 di Console Hub.",Toast.LENGTH_LONG).show()
            startActivity(Intent(this,Ps2BiosActivity::class.java))
            return
        }'''
new3 = '''        if(Ps2BiosActivity.selectedBios(this)==null){
            status.text="PS2 BIOS belum siap • game belum dijalankan"
            AlertDialog.Builder(this)
                .setTitle("PS2 BIOS diperlukan")
                .setMessage("Compressed game sudah berhasil dibuka, tapi ARMSX2 membutuhkan BIOS PS2. Game tidak akan dialihkan otomatis ke layar BIOS.")
                .setPositiveButton("SETUP BIOS"){_,_->startActivity(Intent(this,Ps2BiosActivity::class.java))}
                .setNegativeButton("Batal"){_,_->session.root.deleteRecursively()}
                .setOnCancelListener{session.root.deleteRecursively()}
                .show()
            return
        }'''
assert old3 in s, 'PS2 extracted BIOS block not found'
s = s.replace(old3, new3, 1)

p.write_text(s)
