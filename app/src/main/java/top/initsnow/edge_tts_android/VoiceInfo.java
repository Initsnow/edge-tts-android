package top.initsnow.edge_tts_android;

import java.util.Locale;

import org.json.JSONObject;

public final class VoiceInfo {
    private final String name;
    private final String shortName;
    private final String localeTag;
    private final String gender;
    private final String friendlyName;

    public VoiceInfo(
            String name,
            String shortName,
            String localeTag,
            String gender,
            String friendlyName
    ) {
        this.name = name;
        this.shortName = shortName;
        this.localeTag = localeTag;
        this.gender = gender;
        this.friendlyName = friendlyName;
    }

    public static VoiceInfo fromJson(JSONObject object) {
        return new VoiceInfo(
                object.optString("Name"),
                object.optString("ShortName"),
                object.optString("Locale"),
                object.optString("Gender"),
                object.optString("FriendlyName")
        );
    }

    public String getName() {
        return name;
    }

    public String getShortName() {
        return shortName;
    }

    public String getLocaleTag() {
        return localeTag;
    }

    public String getGender() {
        return gender;
    }

    public String getFriendlyName() {
        return friendlyName;
    }

    public Locale toLocale() {
        return Locale.forLanguageTag(localeTag.replace('_', '-'));
    }

    public boolean matchesIso3(String iso3Language, String iso3Country) {
        Locale locale = toLocale();
        boolean languageMatches = false;
        boolean countryMatches = iso3Country == null || iso3Country.isEmpty();
        try {
            languageMatches = locale.getISO3Language().equalsIgnoreCase(iso3Language);
        } catch (RuntimeException ignored) {
            return false;
        }
        try {
            if (!countryMatches) {
                countryMatches = locale.getISO3Country().equalsIgnoreCase(iso3Country);
            }
        } catch (RuntimeException ignored) {
            countryMatches = false;
        }
        return languageMatches && countryMatches;
    }

    public String getDisplayName() {
        if (friendlyName != null && !friendlyName.isEmpty()) {
            return friendlyName + " (" + shortName + ")";
        }
        return shortName + " • " + localeTag + " • " + gender;
    }
}
