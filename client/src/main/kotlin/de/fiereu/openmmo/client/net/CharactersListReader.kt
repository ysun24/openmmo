package de.fiereu.openmmo.client.net

import de.fiereu.bytecodec.Bool
import de.fiereu.bytecodec.ReadBuffer
import de.fiereu.bytecodec.S32LE
import de.fiereu.bytecodec.U8
import de.fiereu.bytecodec.Utf16LeNullTerminated
import de.fiereu.openmmo.net.game.codecs.CharacterInfoCodecShort
import de.fiereu.openmmo.net.game.codecs.DefaultSkinSetCodec
import de.fiereu.openmmo.net.game.codecs.PokemonCodec
import de.fiereu.openmmo.net.game.codecs.SkinSetCodecNoLeading
import de.fiereu.openmmo.net.game.packets.CharacterEntry
import de.fiereu.openmmo.net.game.packets.CharactersListPacket

/**
 * Reads a character list the way the server writes one. The shared codec reads the character info
 * in its long form, from retail captures, while the server writes the short form.
 */
internal object CharactersListReader {
  fun read(buf: ReadBuffer): CharactersListPacket {
    val count = U8.read(buf)
    val entries =
        List(count) {
          val info = CharacterInfoCodecShort.read(buf)
          val skin = DefaultSkinSetCodec.read(buf)
          val secondary = SkinSetCodecNoLeading.read(buf)
          val guildId =
              if (Bool.read(buf)) {
                Utf16LeNullTerminated.read(buf)
                S32LE.read(buf)
              } else null
          val partySize = U8.read(buf)
          val party = List(partySize) { PokemonCodec.read(buf) }
          CharacterEntry(info, skin, secondary, guildId, party)
        }
    return CharactersListPacket(entries)
  }
}
