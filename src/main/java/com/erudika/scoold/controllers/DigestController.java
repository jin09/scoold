package com.erudika.scoold.controllers;

import com.erudika.para.client.ParaClient;
import com.erudika.para.email.Emailer;
import com.erudika.para.utils.Config;
import com.erudika.para.utils.Pager;
import com.erudika.para.utils.Utils;
import com.erudika.scoold.core.Meme;
import com.erudika.scoold.core.Profile;
import com.erudika.scoold.core.Question;
import com.erudika.scoold.utils.ScooldUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.inject.Inject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.stream.Collectors;

import static com.erudika.scoold.ScooldServer.CONTEXT_PATH;
import static com.erudika.scoold.ScooldServer.getServerURL;
import static com.erudika.scoold.core.Post.DEFAULT_SPACE;
import static com.erudika.scoold.utils.ScooldUtils.EMAIL_ALERTS_PREFIX;

@Component
public class DigestController {
    public static final Logger logger = LoggerFactory.getLogger(QuestionController.class);
    private final ScooldUtils utils;
    private final ParaClient pc;
    @Inject
    private Emailer emailer;

    @Inject
    DigestController(ScooldUtils utils) {
        this.utils = utils;
        this.pc = utils.getParaClient();
    }

    public static String memeOfTheDay() throws IOException {
        StringBuilder result = new StringBuilder();
        URL url = new URL(Config.getConfigParam("meme_url", "http://jin09.pythonanywhere.com/api/RandomMeme"));
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        BufferedReader rd = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        String line;
        while ((line = rd.readLine()) != null) {
            result.append(line);
        }
        rd.close();
        Meme meme = new ObjectMapper().readValue(result.toString(), Meme.class);
        return meme.getMeme();
    }

    @Scheduled(cron = "0 48 5 * * ?")
    public void sendDigestEmail() {
        Map<String, Object> model = new HashMap<String, Object>();

        // 1. get top 5 questions
        Pager p = new Pager(1, 5);
        String query = "*";
        String type = Utils.type(Question.class);
        List<Question> questionslist = pc.findQuery(type, query, p);
        utils.fetchProfiles(questionslist);
        for (Question question : questionslist) {
            Profile postAuthor = question.getAuthor() != null ? question.getAuthor() : pc.read(question.getCreatorid());
            String user_name = postAuthor.getName();
            String user_image_url = postAuthor.getPicture();
            String body = Utils.markdownToHtml(question.getBody());
            String question_url = getServerURL() + CONTEXT_PATH + question.getPostLink(false, false);
            String tagsString = Optional.ofNullable(question.getTags()).orElse(Collections.emptyList()).stream().
                    map(t -> "<span class=\"tag\">" + t + "</span>").
                    collect(Collectors.joining("&nbsp;"));
            String question_title = question.getTitle();
            question.body_html = Utils.formatMessage("<div>{0}</div><br>{1}", body, tagsString);
            question.question_title = question_title;
            question.question_url = question_url;
            question.user_image_url = user_image_url;
            question.user_name = user_name;
        }
        model.put("questions", questionslist);

        // 2. prepare leaderboard
        Pager itemCount = new Pager(1, 10);
        itemCount.setSortby("votes");
        List<Profile> userlist = utils.getParaClient().findQuery(Utils.type(Profile.class), "*", itemCount);
        int rank = 1;
        for (Profile user: userlist){
            user.rank = rank;
            rank += 1;
            user.user_image_url = user.getPicture();
            user.user_name = user.getName();
        }
        if (userlist.size() >= 1) {
            Profile user = userlist.get(0);
            user.first_pos = true;
        }
        if (userlist.size() >= 2) {
            Profile user = userlist.get(1);
            user.second_pos = true;
        }
        if (userlist.size() >= 3) {
            Profile user = userlist.get(2);
            user.third_pos = true;
        }
        model.put("leaderboard", userlist);

        // 3. get the meme image url
        String memeUrl = null;
        try {
            memeUrl = memeOfTheDay();
        } catch (IOException e) {
            memeUrl = "https://i.pinimg.com/originals/42/1f/66/421f66712c82802052999f69e4e5759a.jpg";
        }
        model.put("meme_url", memeUrl);

        // Add other important meta data to the model
        model.put("logourl", Config.getConfigParam("small_logo_url", "https://scoold.com/logo.png"));

        // 4. prepare template
        // 5. send email
        Set<String> emails = new HashSet<String>(utils.getNotificationSubscribers(EMAIL_ALERTS_PREFIX + "daily_digest_email"));
        utils.sendEmailsToSubscribersInSpace(emails, DEFAULT_SPACE, "Delhivery's Stackoverflow Digest",
                utils.compileDigestEmailTemplate(model));
    }

}
