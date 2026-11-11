package com.cratez.core.genre

/** Canonical genres Cratez files music under. EDM is split finely; everything else stays broad. */
enum class Genre(val label: String, val edm: Boolean) {
    HOUSE("House", true),
    DEEP_HOUSE("Deep House", true),
    TECH_HOUSE("Tech House", true),
    PROGRESSIVE_HOUSE("Progressive House", true),
    BASS_HOUSE("Bass House", true),
    ELECTRO_HOUSE("Electro House", true),
    BIG_ROOM("Big Room", true),
    FUTURE_HOUSE("Future House", true),
    AFRO_HOUSE("Afro House", true),
    MELODIC_TECHNO("Melodic House & Techno", true),
    TECHNO("Techno", true),
    HARD_TECHNO("Hard Techno", true),
    MINIMAL("Minimal / Deep Tech", true),
    TRANCE("Trance", true),
    PSYTRANCE("Psytrance", true),
    DUBSTEP("Dubstep", true),
    RIDDIM("Riddim", true),
    DRUM_AND_BASS("Drum & Bass", true),
    JUNGLE("Jungle", true),
    BREAKBEAT("Breaks", true),
    UK_GARAGE("UK Garage", true),
    TRAP("Trap / Bass", true),
    FUTURE_BASS("Future Bass", true),
    HARDSTYLE("Hardstyle", true),
    HARDCORE("Hardcore", true),
    MOOMBAHTON("Moombahton", true),
    AMAPIANO("Amapiano", true),
    ELECTRO("Electro", true),
    SYNTHWAVE("Synthwave", true),
    DOWNTEMPO("Downtempo / Chill", true),
    AMBIENT("Ambient", true),
    NU_DISCO("Nu Disco / Disco", true),
    DANCE_POP("Dance / Pop", true),
    ELECTRONIC("Electronic", true),

    HIP_HOP("Hip-Hop / Rap", false),
    RNB("R&B", false),
    POP("Pop", false),
    ROCK("Rock", false),
    METAL("Metal", false),
    PUNK("Punk", false),
    INDIE("Indie / Alternative", false),
    JAZZ("Jazz", false),
    BLUES("Blues", false),
    SOUL_FUNK("Soul / Funk", false),
    REGGAE("Reggae / Dancehall", false),
    LATIN("Latin / Reggaeton", false),
    AFROBEATS("Afrobeats", false),
    COUNTRY("Country", false),
    FOLK("Folk / Acoustic", false),
    CLASSICAL("Classical", false),
    SOUNDTRACK("Soundtrack", false),
    GOSPEL("Gospel / Christian", false),
    KPOP("K-Pop", false),
    WORLD("World", false),
    OTHER("Other", false);

    companion object {
        fun fromLabel(label: String?): Genre? = entries.firstOrNull { it.label.equals(label, true) || it.name.equals(label, true) }
    }
}

/** Maps free-text genre/tag strings from tags and online services to canonical genres. */
object GenreNormalizer {
    // Order matters: more specific phrases first.
    private val rules: List<Pair<Regex, Genre>> = listOf(
        "melodic (house|techno)|melodic house & techno|organic house|afterlife" to Genre.MELODIC_TECHNO,
        "hard techno|industrial techno|schranz|hardgroove" to Genre.HARD_TECHNO,
        "minimal|deep tech|microhouse|micro house" to Genre.MINIMAL,
        "tech[ -]?house" to Genre.TECH_HOUSE,
        "deep[ -]?house|lo[- ]?fi house" to Genre.DEEP_HOUSE,
        "progressive house|progressive trance|^progressive$" to Genre.PROGRESSIVE_HOUSE,
        "bass house|g[- ]?house|ghetto house|speed house" to Genre.BASS_HOUSE,
        "complextro|electro house|fidget" to Genre.ELECTRO_HOUSE,
        "big room|festival|mainstage" to Genre.BIG_ROOM,
        "future house|slap house|brazilian bass" to Genre.FUTURE_HOUSE,
        "afro[ -]?house|afro tech|3[- ]?step" to Genre.AFRO_HOUSE,
        "amapiano|private school" to Genre.AMAPIANO,
        "psy[- ]?trance|psychedelic trance|goa|full[ -]?on|forest" to Genre.PSYTRANCE,
        "uplifting|vocal trance|trance|tech[ -]?trance" to Genre.TRANCE,
        "riddim|tearout|brostep" to Genre.RIDDIM,
        "dubstep|deathstep|melodic dubstep|chillstep|post[ -]?dubstep" to Genre.DUBSTEP,
        "jungle|ragga jungle" to Genre.JUNGLE,
        "drum (and|&|n'?) ?bass|d ?& ?b|dnb|d'n'b|liquid funk|neurofunk|jump up|dancefloor d" to Genre.DRUM_AND_BASS,
        "uk garage|2[- ]?step|speed garage|bassline|^garage$|uk funky" to Genre.UK_GARAGE,
        "breakbeat|breaks|nu skool|big beat" to Genre.BREAKBEAT,
        "future bass|kawaii|color bass" to Genre.FUTURE_BASS,
        "hardstyle|rawstyle|euphoric hard" to Genre.HARDSTYLE,
        "hardcore|gabber|happy hardcore|frenchcore|uptempo|hard dance" to Genre.HARDCORE,
        "moombah|dembow" to Genre.MOOMBAHTON,
        "edm trap|festival trap|hybrid trap|^trap$|bass music|midtempo|^bass$|wonky" to Genre.TRAP,
        "synthwave|retrowave|outrun|darksynth|vaporwave" to Genre.SYNTHWAVE,
        "^electro$|electro[- ]?funk|miami bass" to Genre.ELECTRO,
        "techno|acid techno|detroit|warehouse" to Genre.TECHNO,
        "nu[- ]?disco|disco house|french house|indie dance|^disco$|italo" to Genre.NU_DISCO,
        "downtempo|chill ?out|chillwave|lo[- ]?fi|trip[- ]?hop|chill|lounge|idm|glitch" to Genre.DOWNTEMPO,
        "ambient|new age|drone" to Genre.AMBIENT,
        "jackin|house|electro swing" to Genre.HOUSE,
        "dance[- ]?pop|eurodance|europop|^dance$|electropop|dance & edm|edm" to Genre.DANCE_POP,
        "electronic|electronica|electro|dance music|club|rave|elektron" to Genre.ELECTRONIC,

        "k-?pop|j-?pop" to Genre.KPOP,
        "hip[- ]?hop|rap|drill|grime|boom bap|phonk|crunk|^trap rap|gangsta" to Genre.HIP_HOP,
        "r&b|rnb|r'n'b|rhythm (and|&) blues|neo[- ]?soul|contemporary r" to Genre.RNB,
        "reggaeton|latin|salsa|bachata|cumbia|merengue|urbano|corridos|regional mexican|bossa" to Genre.LATIN,
        "afrobeat|afro[- ]?pop|afro[- ]?fusion|naija" to Genre.AFROBEATS,
        "reggae|dancehall|dub$|ska" to Genre.REGGAE,
        "metal|metalcore|deathcore|djent" to Genre.METAL,
        "punk|emo|hardcore punk|pop punk" to Genre.PUNK,
        "indie|alternative|shoegaze|post[- ]?rock" to Genre.INDIE,
        "rock|grunge|classic rock" to Genre.ROCK,
        "jazz|swing|bebop|fusion" to Genre.JAZZ,
        "blues" to Genre.BLUES,
        "soul|funk|motown" to Genre.SOUL_FUNK,
        "country|americana|bluegrass" to Genre.COUNTRY,
        "folk|singer[- ]?songwriter|acoustic" to Genre.FOLK,
        "classical|orchestra|baroque|opera|piano" to Genre.CLASSICAL,
        "soundtrack|score|film|video game|anime" to Genre.SOUNDTRACK,
        "gospel|christian|worship|praise" to Genre.GOSPEL,
        "world|african|bollywood|indian|arabic|celtic" to Genre.WORLD,
        "pop|singer|teen" to Genre.POP,
    ).map { Regex(it.first, RegexOption.IGNORE_CASE) to it.second }

    /** ID3v1 numeric genres like "(18)" or "18". */
    private val id3v1 = mapOf(
        0 to "Blues", 2 to "Country", 3 to "Dance", 4 to "Disco", 5 to "Funk", 6 to "Grunge", 7 to "Hip-Hop",
        8 to "Jazz", 9 to "Metal", 10 to "New Age", 13 to "Pop", 14 to "R&B", 15 to "Rap", 16 to "Reggae",
        17 to "Rock", 18 to "Techno", 19 to "Industrial", 24 to "Soundtrack", 26 to "Ambient", 27 to "Trip-Hop",
        31 to "Trance", 32 to "Classical", 35 to "House", 52 to "Electronic", 127 to "Drum & Bass", 129 to "Hardcore",
        138 to "Black Metal", 189 to "Dubstep",
    )

    /** Strings that carry no real genre meaning. */
    private val junk = Regex("^(unknown|other|genre|misc|none|null|n/?a|various|music|default|\\s*)$", RegexOption.IGNORE_CASE)

    fun normalize(raw: String?): Genre? {
        if (raw.isNullOrBlank()) return null
        var s = raw.trim()
        Regex("^\\(?(\\d{1,3})\\)?$").find(s)?.let { s = id3v1[it.groupValues[1].toInt()] ?: return null }
        if (junk.matches(s)) return null
        // "Electronica / Dance" etc: evaluate whole string first, rules are ordered by specificity.
        for ((re, g) in rules) if (re.containsMatchIn(s)) return g
        return null
    }

    /** Generic labels that only tell us "it's electronic" without naming the style. */
    fun isGenericElectronic(g: Genre) = g == Genre.ELECTRONIC || g == Genre.DANCE_POP
}
