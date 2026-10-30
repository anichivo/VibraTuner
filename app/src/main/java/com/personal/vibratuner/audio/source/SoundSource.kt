package com.personal.vibratuner.audio.source

/**
 * Интерфейс источника звука.
 * Заложен как точка расширения, в текущей реализации поддерживаются только локальные файлы (SAF).
 */
interface SoundSource {
    val id: String
    val displayName: String
    val uriString: String
}

/**
 * Источник звука на основе локального файла через Storage Access Framework (SAF persistable URI).
 */
data class LocalFileSoundSource(
    override val id: String,
    override val displayName: String,
    override val uriString: String
) : SoundSource
