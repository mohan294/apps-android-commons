package fr.free.nrw.commons.mwapi;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

import javax.inject.Inject;
import javax.inject.Singleton;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import fr.free.nrw.commons.Media;
import fr.free.nrw.commons.PageTitle;
import fr.free.nrw.commons.achievements.FeaturedImages;
import fr.free.nrw.commons.achievements.FeedbackResponse;
import fr.free.nrw.commons.campaigns.CampaignResponseDTO;
import fr.free.nrw.commons.kvstore.JsonKvStore;
import fr.free.nrw.commons.location.LatLng;
import fr.free.nrw.commons.media.model.MwQueryPage;
import fr.free.nrw.commons.mwapi.model.MwQueryResponse;
import fr.free.nrw.commons.mwapi.model.RecentChange;
import fr.free.nrw.commons.nearby.Place;
import fr.free.nrw.commons.nearby.model.NearbyResponse;
import fr.free.nrw.commons.nearby.model.NearbyResultItem;
import fr.free.nrw.commons.upload.FileUtils;
import fr.free.nrw.commons.utils.DateUtils;
import fr.free.nrw.commons.utils.StringUtils;
import fr.free.nrw.commons.wikidata.model.GetWikidataEditCountResponse;
import io.reactivex.Observable;
import io.reactivex.Single;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import timber.log.Timber;

@Singleton
public class OkHttpJsonApiClient {

    public static final Type mapType = new TypeToken<Map<String, String>>() {
    }.getType();

    private final OkHttpClient okHttpClient;
    private final HttpUrl wikiMediaToolforgeUrl;
    private final String sparqlQueryUrl;
    private final String campaignsUrl;
    private final String commonsBaseUrl;
    private final JsonKvStore defaultKvStore;
    private Gson gson;


    @Inject
    public OkHttpJsonApiClient(OkHttpClient okHttpClient,
                               HttpUrl wikiMediaToolforgeUrl,
                               String sparqlQueryUrl,
                               String campaignsUrl,
                               String commonsBaseUrl,
                               JsonKvStore defaultKvStore,
                               Gson gson) {
        this.okHttpClient = okHttpClient;
        this.wikiMediaToolforgeUrl = wikiMediaToolforgeUrl;
        this.sparqlQueryUrl = sparqlQueryUrl;
        this.campaignsUrl = campaignsUrl;
        this.commonsBaseUrl = commonsBaseUrl;
        this.defaultKvStore = defaultKvStore;
        this.gson = gson;
    }

    @NonNull
    public Single<Integer> getUploadCount(String userName) {
        HttpUrl.Builder urlBuilder = wikiMediaToolforgeUrl.newBuilder();
        urlBuilder
                .addPathSegments("/uploadsbyuser.py")
                .addQueryParameter("user", userName);
        Request request = new Request.Builder()
                .url(urlBuilder.build())
                .build();

        return Single.fromCallable(() -> {
            Response response = okHttpClient.newCall(request).execute();
            if (response != null && response.isSuccessful()) {
                return Integer.parseInt(response.body().string().trim());
            }
            return 0;
        });
    }

    @NonNull
    public Single<Integer> getWikidataEdits(String userName) {
        HttpUrl.Builder urlBuilder = wikiMediaToolforgeUrl.newBuilder();
        urlBuilder
                .addPathSegments("/wikidataedits.py")
                .addQueryParameter("user", userName);
        Request request = new Request.Builder()
                .url(urlBuilder.build())
                .build();

        return Single.fromCallable(() -> {
            Response response = okHttpClient.newCall(request).execute();
            if (response != null &&
                    response.isSuccessful() && response.body() != null) {
                String json = response.body().string();
                if (json == null) {
                    return 0;
                }
                GetWikidataEditCountResponse countResponse = gson.fromJson(json, GetWikidataEditCountResponse.class);
                return countResponse.getWikidataEditCount();
            }
            return 0;
        });
    }

    /**
     * This takes userName as input, which is then used to fetch the feedback/achievements
     * statistics using OkHttp and JavaRx. This function return JSONObject
     *
     * @param userName MediaWiki user name
     * @return
     */
    public Single<FeedbackResponse> getAchievements(String userName) {
        final String fetchAchievementUrlTemplate =
                wikiMediaToolforgeUrl + "/feedback.py";
        return Single.fromCallable(() -> {
            String url = String.format(
                    Locale.ENGLISH,
                    fetchAchievementUrlTemplate,
                    new PageTitle(userName).getText());
            HttpUrl.Builder urlBuilder = HttpUrl.parse(url).newBuilder();
            urlBuilder.addQueryParameter("user", userName);
            Timber.i("Url %s", urlBuilder.toString());
            Request request = new Request.Builder()
                    .url(urlBuilder.toString())
                    .build();
            Response response = okHttpClient.newCall(request).execute();
            if (response != null && response.body() != null && response.isSuccessful()) {
                String json = response.body().string();
                if (json == null) {
                    return null;
                }
                Timber.d("Response for achievements is %s", json);
                try {
                    return gson.fromJson(json, FeedbackResponse.class);
                } catch (Exception e) {
                    return new FeedbackResponse("", 0, 0, 0, new FeaturedImages(0, 0), 0, "", 0);
                }


            }
            return null;
        });
    }

    public Observable<List<Place>> getNearbyPlaces(LatLng cur, String lang, double radius) throws IOException {
        String wikidataQuery = FileUtils.readFromResource("/queries/nearby_query.rq");
        String query = wikidataQuery
                .replace("${RAD}", String.format(Locale.ROOT, "%.2f", radius))
                .replace("${LAT}", String.format(Locale.ROOT, "%.4f", cur.getLatitude()))
                .replace("${LONG}", String.format(Locale.ROOT, "%.4f", cur.getLongitude()))
                .replace("${LANG}", lang);

        HttpUrl.Builder urlBuilder = HttpUrl
                .parse(sparqlQueryUrl)
                .newBuilder()
                .addQueryParameter("query", query)
                .addQueryParameter("format", "json");

        Request request = new Request.Builder()
                .url(urlBuilder.build())
                .build();

        return Observable.fromCallable(() -> {
            Response response = okHttpClient.newCall(request).execute();
            if (response != null && response.body() != null && response.isSuccessful()) {
                String json = response.body().string();
                if (json == null) {
                    return new ArrayList<>();
                }
                NearbyResponse nearbyResponse = gson.fromJson(json, NearbyResponse.class);
                List<NearbyResultItem> bindings = nearbyResponse.getResults().getBindings();
                List<Place> places = new ArrayList<>();
                for (NearbyResultItem item : bindings) {
                    places.add(Place.from(item));
                }
                return places;
            }
            return new ArrayList<>();
        });
    }

    public Single<CampaignResponseDTO> getCampaigns() {
        return Single.fromCallable(() -> {
            Request request = new Request.Builder().url(campaignsUrl)
                    .build();
            Response response = okHttpClient.newCall(request).execute();
            if (response != null && response.body() != null && response.isSuccessful()) {
                String json = response.body().string();
                if (json == null) {
                    return null;
                }
                return gson.fromJson(json, CampaignResponseDTO.class);
            }
            return null;
        });
    }

    /**
     * The method returns the picture of the day
     *
     * @return Media object corresponding to the picture of the day
     */
    @Nullable
    public Single<Media> getPictureOfTheDay() {
        String template = "Template:Potd/" + DateUtils.getCurrentDate();
        HttpUrl.Builder urlBuilder = HttpUrl
                .parse(commonsBaseUrl)
                .newBuilder()
                .addQueryParameter("action", "query")
                .addQueryParameter("generator", "images")
                .addQueryParameter("format", "json")
                .addQueryParameter("titles", template)
                .addQueryParameter("prop", "imageinfo")
                .addQueryParameter("iiprop", "url|extmetadata");

        Request request = new Request.Builder()
                .url(urlBuilder.build())
                .build();

        return Single.fromCallable(() -> {
            Response response = okHttpClient.newCall(request).execute();
            if (response.body() != null && response.isSuccessful()) {
                String json = response.body().string();
                MwQueryResponse mwQueryPage = gson.fromJson(json, MwQueryResponse.class);
                return Media.from(mwQueryPage.query().firstPage());
            }
            return null;
        });
    }

    /**
     * This method takes the keyword and queryType as input and returns a list of  Media objects filtered using image generator query
     * It uses the generator query API to get the images searched using a query, 10 at a time.
     * @param queryType queryType can be "search" OR "category"
     * @param keyword
     * @return
     */
    @Nullable
    public Single<List<Media>> getMediaList(String queryType, String keyword) {
        HttpUrl.Builder urlBuilder = HttpUrl
                .parse(commonsBaseUrl)
                .newBuilder()
                .addQueryParameter("action", "query")
                .addQueryParameter("format", "json");


        if (queryType.equals("search")) {
            appendSearchParam(keyword, urlBuilder);
        } else {
            appendCategoryParams(keyword, urlBuilder);
        }

        appendQueryContinueValues(keyword, urlBuilder);

        Request request = new Request.Builder()
                .url(appendMediaProperties(urlBuilder).build())
                .build();

        return Single.fromCallable(() -> {
            Response response = okHttpClient.newCall(request).execute();
            List<Media> mediaList = new ArrayList<>();
            if (response.body() != null && response.isSuccessful()) {
                String json = response.body().string();
                MwQueryResponse mwQueryResponse = gson.fromJson(json, MwQueryResponse.class);
                putContinueValues(keyword, mwQueryResponse.continuation());
                if (mwQueryResponse.query() == null) {
                    return mediaList;
                }
                List<MwQueryPage> pages = mwQueryResponse.query().pages();
                for (MwQueryPage page : pages) {
                    Media media = Media.from(page);
                    if (media != null) {
                        mediaList.add(media);
                    }
                }
            }
            return mediaList;
        });
    }

    /**
     * Whenever imageInfo is fetched, these common properties can be specified for the API call
     * https://www.mediawiki.org/wiki/API:Imageinfo
     * @param builder
     * @return
     */
    private HttpUrl.Builder appendMediaProperties(HttpUrl.Builder builder) {
        builder.addQueryParameter("prop", "imageinfo")
                .addQueryParameter("iiprop", "url|extmetadata")
                .addQueryParameter("iiextmetadatafilter", "DateTime|Categories|GPSLatitude|GPSLongitude|ImageDescription|DateTimeOriginal|Artist|LicenseShortName");

        String language = Locale.getDefault().getLanguage();
        if (!StringUtils.isNullOrWhiteSpace(language)) {
            builder.addQueryParameter("iiextmetadatalanguage", language);
        }

        return builder;
    }

    /**
     * Append params for search query.
     * @param query
     * @param urlBuilder
     */
    private void appendSearchParam(String query, HttpUrl.Builder urlBuilder) {
        urlBuilder.addQueryParameter("generator", "search")
                .addQueryParameter("gsrwhat", "text")
                .addQueryParameter("gsrnamespace", "6")
                .addQueryParameter("gsrlimit", "25")
                .addQueryParameter("gsrsearch", query);
    }

    /**
     * It takes a urlBuilder and appends all the continue values as query parameters
     * @param query
     * @param urlBuilder
     */
    private void appendQueryContinueValues(String query, HttpUrl.Builder urlBuilder) {
        Map<String, String> continueValues = getContinueValues(query);
        if (continueValues != null && continueValues.size() > 0) {
            for (Map.Entry<String, String> entry : continueValues.entrySet()) {
                urlBuilder.addQueryParameter(entry.getKey(), entry.getValue());
            }
        }
    }

    private void appendCategoryParams(String categoryName, HttpUrl.Builder urlBuilder) {
        urlBuilder.addQueryParameter("generator", "categorymembers")
                .addQueryParameter("gcmtype", "file")
                .addQueryParameter("gcmtitle", categoryName)
                .addQueryParameter("gcmsort", "timestamp")//property to sort by;timestamp
                .addQueryParameter("gcmdir", "desc")//in which direction to sort;descending
                .addQueryParameter("gcmlimit", "10");
    }

    /**
     * Stores the continue values for action=query
     * These values are sent to the server in the subsequent call to fetch results after this point
     * @param keyword
     * @param values
     */
    private void putContinueValues(String keyword, Map<String, String> values) {
        defaultKvStore.putJson("query_continue_" + keyword, values);
    }

    /**
     * Retrieves a map of continue values from shared preferences.
     * These values are appended to the next API call
     * @param keyword
     * @return
     */
    private Map<String, String> getContinueValues(String keyword) {
        return defaultKvStore.getJson("query_continue_" + keyword, mapType);
    }

    /**
     * Returns recent changes on commons
     * @return list of recent changes made
     */
    @Nullable
    public Single<List<RecentChange>> getRecentFileChanges() {
        final int RANDOM_SECONDS = 60 * 60 * 24 * 30;
        final String FILE_NAMESPACE = "6";
        Random r = new Random();
        Date now = new Date();
        Date startDate = new Date(now.getTime() - r.nextInt(RANDOM_SECONDS) * 1000L);

        HttpUrl.Builder urlBuilder = HttpUrl
                .parse(commonsBaseUrl)
                .newBuilder()
                .addQueryParameter("action", "query")
                .addQueryParameter("format", "json")
                .addQueryParameter("list", "recentchanges")
                .addQueryParameter("rcstart", DateUtils.formatMWDate(startDate))
                .addQueryParameter("rcnamespace", FILE_NAMESPACE)
                .addQueryParameter("rcprop", "title|ids")
                .addQueryParameter("rctype", "new|log")
                .addQueryParameter("rctoponly", "1");

        Request request = new Request.Builder()
                .url(urlBuilder.build())
                .build();

        return Single.fromCallable(() -> {
            Response response = okHttpClient.newCall(request).execute();
            if (response.body() != null && response.isSuccessful()) {
                String json = response.body().string();
                MwQueryResponse mwQueryPage = gson.fromJson(json, MwQueryResponse.class);
                return mwQueryPage.query().getRecentchanges();
            }
            return new ArrayList<>();
        });
    }

    /**
     * Returns the first revision of the file
     *
     * @return Revision object
     */
    @Nullable
    public Single<MwQueryPage.Revision> getFirstRevisionOfFile(String filename) {
        HttpUrl.Builder urlBuilder = HttpUrl
                .parse(commonsBaseUrl)
                .newBuilder()
                .addQueryParameter("action", "query")
                .addQueryParameter("format", "json")
                .addQueryParameter("prop", "revisions")
                .addQueryParameter("rvprop", "timestamp|ids|user")
                .addQueryParameter("titles", filename)
                .addQueryParameter("rvdir", "newer")
                .addQueryParameter("rvlimit", "1");

        Request request = new Request.Builder()
                .url(urlBuilder.build())
                .build();

        return Single.fromCallable(() -> {
            Response response = okHttpClient.newCall(request).execute();
            if (response.body() != null && response.isSuccessful()) {
                String json = response.body().string();
                MwQueryResponse mwQueryPage = gson.fromJson(json, MwQueryResponse.class);
                return mwQueryPage.query().firstPage().revisions().get(0);
            }
            return null;
        });
    }
}
