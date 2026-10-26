package fr.lyonmc.communaute;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sites de vote français qui vérifient un vote sans NuVotifier : le serveur leur demande si l'IP (ou le pseudo)
 * du joueur a voté. Chaque site est reconnu grâce au lien de vote ; certains demandent en plus une clé (« cle: »)
 * à copier depuis le panneau du serveur sur le site.
 */
final class VoteSites {

    /** Résultat d'une vérification. */
    enum Result { VOTED, NOT_VOTED, UNKNOWN }

    /**
     * Un site connu : domaine, adresse de vérification ({id} = identifiant ou clé du serveur, {ip}, {pseudo}),
     * motif qui lit l'identifiant dans le lien de vote (null = clé à mettre dans « cle: »), et lecture de la réponse.
     */
    record Provider(String domain, String url, Pattern idInLink, String keyLabel, Answer answer) {
        boolean byName() {
            return url.contains("{pseudo}");
        }
    }

    /** Comment lire la réponse : un champ JSON (chemin « a.b ») qui doit valoir « expected », ou le texte entier. */
    record Answer(String field, String expected, boolean invert) {
        static Answer json(String field, String expected) {
            return new Answer(field, expected, false);
        }

        /** Le champ vaut « expected » quand le joueur n'a PAS encore voté (ex. : canVote = true). */
        static Answer jsonNot(String field, String expected) {
            return new Answer(field, expected, true);
        }

        static Answer text(String expected) {
            return new Answer(null, expected, false);
        }
    }

    static final List<Provider> KNOWN = List.of(
            new Provider("serveurs-minecraft.org", "https://www.serveurs-minecraft.org/api/is_valid_vote.php?id={id}&ip={ip}&duration=60&format=json",
                    Pattern.compile("serveurs-minecraft\\.org/vote\\.php\\?id=(\\d+)"), null, Answer.json("votes", "1")),
            new Provider("serveur-prive.net", "https://serveur-prive.net/api/v1/servers/{id}/votes/{ip}",
                    null, "la clé API (panneau du serveur sur serveur-prive.net)", Answer.json("success", "true")),
            new Provider("top-serveurs.net", "https://api.top-serveurs.net/v1/votes/check-ip?server_token={id}&ip={ip}",
                    null, "le token du serveur (panneau du serveur sur top-serveurs.net)", Answer.json("code", "200")),
            new Provider("serveur-minecraft-vote.fr", "https://serveur-minecraft-vote.fr/api/v1/servers/{id}/vote/{ip}",
                    Pattern.compile("serveur-minecraft-vote\\.fr/serveur/(\\d+)"), null, Answer.jsonNot("canVote", "true")),
            new Provider("liste-serveurs-minecraft.org", "https://api.liste-serveurs-minecraft.org/vote/vote_verification.php?server_id={id}&ip={ip}&duration=60",
                    null, "l'identifiant du serveur (server_id) sur liste-serveurs-minecraft.org", Answer.text("1")),
            new Provider("serveurs-mc.net", "https://serveurs-mc.net/api/hasVote/{id}/{ip}/60",
                    Pattern.compile("serveurs-mc\\.net/serveur/(\\d+)"), null, Answer.json("hasVote", "true")),
            new Provider("serveur-minecraft.com", "https://serveur-minecraft.com/api/1/vote/{id}/{ip}/json",
                    Pattern.compile("serveur-minecraft\\.com/(\\d+)"), null, Answer.json("vote", "1")),
            new Provider("serveur-minecraft.fr", "https://serveur-minecraft.fr/api-{id}_{ip}.json",
                    Pattern.compile("serveur-minecraft\\.fr/[\\w-]+\\.(\\d+)"), null, Answer.json("status", "Success")),
            new Provider("liste-serveurs.fr", "https://www.liste-serveurs.fr/api/checkVote/{id}/{ip}",
                    Pattern.compile("liste-serveurs\\.fr/[\\w-]+\\.(\\d+)"), null, Answer.json("success", "true")),
            new Provider("liste-serv-minecraft.fr", "https://liste-serv-minecraft.fr/api/check?server={id}&ip={ip}",
                    Pattern.compile("liste-serv-minecraft\\.fr/serveur\\?id=(\\d+)"), null, Answer.json("status", "200")),
            new Provider("serveurliste.com", "https://serveurliste.com/api/vote?ip_address={ip}&api_token={id}",
                    null, "la clé API (panneau du serveur sur serveurliste.com)", Answer.json("data.voted", "true")),
            new Provider("minecraft.fr", "https://minecraft.fr/wp-json/serveursmc/v1/votes/{pseudo}?api_key={id}",
                    null, "la clé API (panneau du serveur sur minecraft.fr)", Answer.json("has_voted", "true")),
            new Provider("minecraft-mp.com", "https://minecraft-mp.com/api/?object=votes&element=claim&key={id}&username={pseudo}",
                    null, "la clé API (panneau du serveur sur minecraft-mp.com)", Answer.text("1"))
    );

    private VoteSites() {
    }

    /** Le lien sans « https:// » ni « www. », en minuscules pour le domaine. */
    private static String bare(String link) {
        String s = link.trim().replaceFirst("(?i)^https?://", "");
        if (s.toLowerCase(Locale.ROOT).startsWith("www.")) s = s.substring(4);
        return s;
    }

    static Provider find(String link) {
        String b = bare(link).toLowerCase(Locale.ROOT);
        for (Provider p : KNOWN) {
            if (b.startsWith(p.domain() + "/") || b.equals(p.domain()) || b.startsWith(p.domain() + "?")) return p;
        }
        return null;
    }

    /** Identifiant du serveur lu dans le lien de vote (sites qui n'ont pas besoin de clé). */
    static String idFromLink(Provider p, String link) {
        if (p.idInLink() == null) return null;
        Matcher m = p.idInLink().matcher(bare(link));
        return m.find() ? m.group(1) : null;
    }

    /** Réponse d'un site (code HTTP + texte) → a voté / n'a pas voté / on ne sait pas. */
    static Result read(Answer a, int status, String body) {
        if (status >= 500 || status == 401 || status == 403 || body == null) return Result.UNKNOWN;
        String b = body.trim();
        if (a == null) return readGeneric(status, b);
        if (a.field() == null) {
            if (status != 200) return Result.NOT_VOTED;
            return b.equals(a.expected()) || b.equalsIgnoreCase("\"" + a.expected() + "\"") ? Result.VOTED : Result.NOT_VOTED;
        }
        JsonElement v = field(b, a.field());
        if (v == null) return status == 200 ? Result.UNKNOWN : Result.NOT_VOTED;
        boolean match = same(v, a.expected());
        return (match != a.invert()) ? Result.VOTED : Result.NOT_VOTED;
    }

    /** Adresse de vérification écrite à la main : « 1 », « true », ou du JSON avec un champ connu. */
    private static Result readGeneric(int status, String b) {
        if (status != 200) return Result.NOT_VOTED;
        if (b.equals("1") || b.equalsIgnoreCase("true")) return Result.VOTED;
        if (!b.startsWith("{")) return Result.NOT_VOTED;
        for (String[] f : new String[][]{{"votes", "1"}, {"vote", "1"}, {"hasVote", "true"}, {"has_voted", "true"}, {"voted", "true"},
                {"data.voted", "true"}, {"success", "true"}}) {
            JsonElement v = field(b, f[0]);
            if (v == null) continue;
            if (f[0].equals("votes")) {
                try {
                    return v.getAsInt() >= 1 ? Result.VOTED : Result.NOT_VOTED;
                } catch (RuntimeException e) {
                    continue;
                }
            }
            return same(v, f[1]) ? Result.VOTED : Result.NOT_VOTED;
        }
        return Result.NOT_VOTED;
    }

    private static boolean same(JsonElement v, String expected) {
        if (v == null || v.isJsonNull() || !v.isJsonPrimitive()) return false;
        String s = v.getAsString();
        if (s.equalsIgnoreCase(expected)) return true;
        try {
            return Double.parseDouble(s) == Double.parseDouble(expected);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** Valeur d'un champ JSON (« data.voted » = champ voted dans data). */
    static JsonElement field(String body, String path) {
        try {
            JsonElement e = JsonParser.parseString(body);
            for (String part : path.split("\\.")) {
                if (e == null || !e.isJsonObject()) return null;
                JsonObject o = e.getAsJsonObject();
                if (!o.has(part)) return null;
                e = o.get(part);
            }
            return e;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** Date du dernier vote si le site la donne (pour ne compter chaque vote qu'une fois). */
    static String voteDate(String body) {
        for (String f : new String[]{"lastVoteDate", "last_vote", "date", "voted_at", "data.voted_at"}) {
            JsonElement v = field(body, f);
            if (v != null && v.isJsonPrimitive() && !v.getAsString().isBlank()) return v.getAsString();
        }
        return null;
    }
}
