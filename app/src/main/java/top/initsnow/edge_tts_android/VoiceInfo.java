package top.initsnow.edge_tts_android;

import java.util.Locale;

import org.json.JSONObject;

public final class VoiceInfo {
    private final String name;
    private final String shortName;
    private final String localeTag;
    private final String gender;
    private final String friendlyName;
    private final Locale locale;
    private final String iso3Language;
    private final String iso3Country;
    private final String normalizedSearchText;

    public VoiceInfo(
            String name,
            String shortName,
            String localeTag,
            String gender,
            String friendlyName
    ) {
        this.name = valueOrEmpty(name);
        this.shortName = valueOrEmpty(shortName);
        this.localeTag = valueOrEmpty(localeTag);
        this.gender = valueOrEmpty(gender);
        this.friendlyName = valueOrEmpty(friendlyName);
        this.locale = Locale.forLanguageTag(this.localeTag.replace('_', '-'));
        this.iso3Language = computeIso3Language(locale);
        this.iso3Country = computeIso3Country(locale);
        this.normalizedSearchText = String.join(
                "\n",
                this.shortName,
                this.localeTag,
                this.name,
                this.friendlyName,
                this.gender
        ).toLowerCase(Locale.US);
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
        return locale;
    }

    public boolean matchesIso3(String iso3Language, String iso3Country) {
        boolean countryMatches = iso3Country == null || iso3Country.isEmpty();
        if (this.iso3Language.isEmpty() || !this.iso3Language.equalsIgnoreCase(iso3Language)) {
            return false;
        }
        if (!countryMatches) {
            countryMatches = !this.iso3Country.isEmpty()
                    && this.iso3Country.equalsIgnoreCase(iso3Country);
        }
        return countryMatches;
    }

    public boolean matchesIso3Language(String iso3Language) {
        return !this.iso3Language.isEmpty() && this.iso3Language.equalsIgnoreCase(iso3Language);
    }

    public String getDisplayName() {
        if (friendlyName != null && !friendlyName.isEmpty()) {
            return friendlyName + " (" + shortName + ")";
        }
        return shortName + " • " + localeTag + " • " + gender;
    }

    public boolean matchesQuery(String query) {
        return normalizedSearchText.contains(query);
    }

    private static String computeIso3Language(Locale locale) {
        try {
            return locale.getISO3Language();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static String computeIso3Country(Locale locale) {
        try {
            return locale.getISO3Country();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }
}
