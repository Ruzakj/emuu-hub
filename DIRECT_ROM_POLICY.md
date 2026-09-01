# Direct ROM storage policy

Emu Hub launches normal game files directly from internal storage or SD storage without duplicating them into app cache.

Temporary cache is reserved for formats that must be unpacked or transformed first, including ZIP/7Z/TAR-family archives and ECM decoding. Extracted temporary files are removed after the emulator session.

This policy applies to internal emulator engines; Switch remains delegated to Eden through Android URI access.
