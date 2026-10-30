package com.personal.vibratuner.audio.eq

/**
 * Аудиодвижок эквалайзера. Живёт внутри единственного координатора
 * воспроизведения (сервиса) и НЕ создаёт собственный путь воспроизведения:
 * только подключает эффекты и скорость к уже существующим плеерам.
 *
 * Управляет КАЖДЫМ плеером координатора (музыка и все микрозвуки):
 * [attach] подключает эффекты/скорость до первого play, [apply] переприменяет
 * настройки на уже играющих звуках, [detach]/[releaseAll] симметрично
 * освобождают эффекты. При `enabled = false` ничего не применяется (эффекты
 * освобождаются, скорость сбрасывается в 1x), но сохранённые значения не теряются.
 *
 * Каждый эффект создаётся и применяется в собственном try/catch: сбой одного
 * эффекта логируется и пропускается, остальные продолжают работать. Из движка
 * НИКОГДА не выбрасывается исключение.
 */
class EqualizerEngine(
    private val effectsFactory: AudioEffectsFactory,
    private val log: (String) -> Unit
) {
    private var settings: EqualizerSettings = EqualizerSettings.DEFAULT
    private val players = mutableListOf<PlayerState>()

    private class PlayerState(
        val player: EqualizerPlayer,
        val listener: AudioSessionIdListener,
        var effects: PlayerEffects? = null
    ) {
        fun releaseEffects() {
            effects?.releaseAll()
            effects = null
        }
    }

    private class PlayerEffects {
        var equalizer: EqualizerEffect? = null
        var loudness: LoudnessEnhancerEffect? = null

        fun releaseAll() {
            equalizer?.let { runCatching { it.release() } }
            loudness?.let { runCatching { it.release() } }
            equalizer = null
            loudness = null
        }
    }

    /** Подключает плеер и сразу применяет ТЕКУЩИЕ настройки (до первого play). */
    fun attach(player: EqualizerPlayer) {
        if (players.any { it.player === player }) return
        val listener = AudioSessionIdListener { id -> onSessionIdChanged(player, id) }
        val state = PlayerState(player, listener)
        players.add(state)
        try {
            player.addAudioSessionIdListener(listener)
        } catch (e: Exception) {
            log("Не удалось подписаться на смену audioSessionId: ${e.message}")
        }
        applyTo(state)
    }

    /** Отключает плеер и освобождает его эффекты. Повторный вызов — безопасный no-op. */
    fun detach(player: EqualizerPlayer) {
        val state = players.firstOrNull { it.player === player } ?: return
        players.remove(state)
        try {
            player.removeAudioSessionIdListener(state.listener)
        } catch (e: Exception) {
            log("Не удалось отписаться от смены audioSessionId: ${e.message}")
        }
        state.releaseEffects()
    }

    /** Переприменяет настройки на всех подключённых плеерах (в т.ч. переключатель ON/OFF). */
    fun apply(settings: EqualizerSettings) {
        this.settings = EqualizerSettings.sanitize(settings)
        players.toList().forEach { applyTo(it) }
    }

    /** Отключает все плееры и освобождает все эффекты (shutdown сервиса). */
    fun releaseAll() {
        players.toList().forEach { detach(it.player) }
        players.clear()
    }

    private fun onSessionIdChanged(player: EqualizerPlayer, newId: Int) {
        val state = players.firstOrNull { it.player === player } ?: return
        if (newId <= 0) return
        // Старые эффекты привязаны к прежнему id — освобождаем и пересоздаём.
        state.releaseEffects()
        applyTo(state)
    }

    private fun applyTo(state: PlayerState) {
        val s = settings
        val speed = if (s.enabled) s.speed else 1.0f
        try {
            state.player.setPlaybackParameters(speed, speed)
        } catch (e: Exception) {
            log("Не удалось применить скорость воспроизведения: ${e.message}")
        }
        // Плеер мог быть освобождён/отключён прямо во время apply (release сервиса
        // или detach из колбэка): для отсоединённого плеера эффекты не создаём.
        if (!isAttached(state)) return

        if (!s.enabled) {
            state.releaseEffects()
            return
        }

        // Session id ещё не назначен: эффекты откладываются до onAudioSessionIdChanged.
        val sessionId = state.player.audioSessionId
        if (sessionId <= 0) return

        if (state.effects == null) {
            val created = createEffects(sessionId)
            if (!isAttached(state)) {
                // Плеер освободили, пока фабрика создавала эффекты: освобождаем их
                // немедленно, иначе они остались бы висеть на несуществующем плеере.
                created.releaseAll()
                return
            }
            state.effects = created
        }
        val effects = state.effects ?: return
        configureEqualizer(effects.equalizer, s)
        configureLoudness(effects.loudness, s)
    }

    private fun isAttached(state: PlayerState): Boolean =
        players.any { it === state }

    private fun createEffects(sessionId: Int): PlayerEffects {
        val effects = PlayerEffects()
        effects.equalizer = try {
            effectsFactory.createEqualizer(sessionId)
        } catch (e: Exception) {
            log("Эквалайзер (полосы) недоступен: ${e.message}")
            null
        }
        effects.loudness = try {
            effectsFactory.createLoudnessEnhancer(sessionId)
        } catch (e: Exception) {
            log("LoudnessEnhancer недоступен: ${e.message}")
            null
        }
        return effects
    }

    /**
     * Бас и ВЧ живут ТОЛЬКО в полосах `Equalizer` (BassBoost умеет лишь
     * поднимать, поэтому срез им невозможен). Диапазон уровней берётся у
     * устройства; он же публикуется для подписей экрана.
     */
    private fun configureEqualizer(eq: EqualizerEffect?, s: EqualizerSettings) {
        if (eq == null) return
        try {
            val range = eq.bandLevelRangeMillibels()
            EqualizerBandRange.publish(range)
            val active = s.bassPercent != EqualizerSettings.TONE_NEUTRAL_PERCENT ||
                s.treblePercent != EqualizerSettings.TONE_NEUTRAL_PERCENT
            if (active) {
                val centers = IntArray(eq.numberOfBands) { eq.centerFreqMilliHz(it) }
                val levels = EqualizerMath.bandLevelsMillibels(
                    bassPercent = s.bassPercent,
                    treblePercent = s.treblePercent,
                    bandCenterFreqsMilliHz = centers,
                    minLevel = range.first,
                    maxLevel = range.last
                )
                for (band in 0 until eq.numberOfBands) {
                    eq.setBandLevel(band, levels[band])
                }
                eq.setEnabled(true)
            } else {
                // Нейтральные бас/ВЧ: полосы не обрабатываются.
                eq.setEnabled(false)
            }
        } catch (e: Exception) {
            log("Не удалось применить полосы эквалайзера: ${e.message}")
        }
    }

    private fun configureLoudness(le: LoudnessEnhancerEffect?, s: EqualizerSettings) {
        if (le == null) return
        try {
            if (s.overboostPercent > 0) {
                le.setTargetGain(EqualizerMath.overboostGainMillibels(s.overboostPercent))
                le.setEnabled(true)
            } else {
                le.setEnabled(false)
            }
        } catch (e: Exception) {
            log("Не удалось применить LoudnessEnhancer: ${e.message}")
        }
    }
}
