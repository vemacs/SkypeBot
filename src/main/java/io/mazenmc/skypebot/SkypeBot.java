package io.mazenmc.skypebot;

import com.google.code.chatterbotapi.ChatterBotFactory;
import com.google.code.chatterbotapi.ChatterBotSession;
import com.google.code.chatterbotapi.ChatterBotType;
import in.kyle.ezskypeezlife.EzSkype;
import in.kyle.ezskypeezlife.api.SkypeConversationType;
import in.kyle.ezskypeezlife.api.SkypeCredentials;
import in.kyle.ezskypeezlife.api.obj.SkypeConversation;
import in.kyle.ezskypeezlife.api.obj.SkypeMessage;
import in.kyle.ezskypeezlife.events.conversation.SkypeMessageReceivedEvent;
import io.mazenmc.skypebot.api.API;
import io.mazenmc.skypebot.engine.bot.ModuleManager;
import io.mazenmc.skypebot.handler.CooldownHandler;
import io.mazenmc.skypebot.stat.StatisticsManager;
import io.mazenmc.skypebot.utils.*;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.restlet.Component;
import org.restlet.Server;
import org.restlet.data.Protocol;
import twitter4j.Twitter;
import twitter4j.TwitterFactory;
import twitter4j.conf.ConfigurationBuilder;

import java.io.*;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.LinkedList;
import java.util.List;
import java.util.Properties;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class SkypeBot {

    private static SkypeBot instance;
    Connection database;
    private Server apiServer;
    private ChatterBotSession bot;
    private twitter4j.Twitter twitter;
    private boolean locked = false;
    private EzSkype skype;
    private UpdateChecker updateChecker;
    private CooldownHandler cooldownHandler;
    private String username;
    private String password;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    public SkypeBot(String[] args) {
        instance = this;

        try {
            bot = new ChatterBotFactory().create(ChatterBotType.CLEVERBOT).createSession();
        } catch (Exception ignored) {
        }

        ModuleManager.loadModules("io.mazenmc.skypebot.modules");

        try {
            loadConfig();
            loadSkype();
        } catch (Exception e) {
            e.printStackTrace();
        }

        updateChecker = new UpdateChecker();
        updateChecker.start();

        Component c = new Component();
        c.getServers().add(Protocol.HTTP, 25565);

        API apiV1 = new API();
        c.getDefaultHost().attach("", apiV1);
        c.getDefaultHost().attach("/v1", apiV1);

        c.getLogService().setEnabled(false);

        try {
            c.start();
        } catch (Exception ignored) {
        }

        Properties connectionProps = new Properties();
        connectionProps.put("user", "skype_bot");
        connectionProps.put("password", "skype_bot");

        try {
            database = DriverManager.getConnection("jdbc:mysql://localhost:3306/skype_bot", connectionProps);
        } catch (SQLException e) {
        }

        List<String> twitterInfo = Utils.readAllLines("twitter_auth");

        ConfigurationBuilder cb = new ConfigurationBuilder();
        cb.setDebugEnabled(true)
                .setOAuthConsumerKey(twitterInfo.get(0))
                .setOAuthConsumerSecret(twitterInfo.get(1))
                .setOAuthAccessToken(twitterInfo.get(2))
                .setOAuthAccessTokenSecret(twitterInfo.get(3));
        twitter = new TwitterFactory(cb.build()).getInstance();

        cooldownHandler = new CooldownHandler();
        StatisticsManager.instance().loadStatistics();
        new Thread(new ChatCleaner(), "ChatCleaner Thread").start();

        Resource.sendMessage("/me " + Resource.VERSION + " initialized!");
    }

    public void loadSkype() {
        scheduler.scheduleAtFixedRate(() -> {
            EzSkype oldSkype = skype;
            EzSkype newSkype = new EzSkype(new SkypeCredentials(username, password));
            try {
                newSkype.login();
                System.out.println("Logged in with username " + username);
                newSkype.getEventManager().registerEvents(new SkypeEventListener());
                System.out.println("Reassigned new skype");
                skype = newSkype;
                if (oldSkype != null) oldSkype.logout();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, 0, 3, TimeUnit.HOURS);
    }

    public void loadConfig() throws IOException {
        Properties prop = new Properties();
        File config = new File("bot.properties");
        if (!config.exists()) {
            try (OutputStream output = new FileOutputStream(config)) {
                prop.setProperty("username", "your.skype.username");
                prop.setProperty("password", "your.skype.password");
                prop.store(output, null);
            }
            System.out.println("Generated default configuration. Exiting.");
            return;
        }

        try (InputStream input = new FileInputStream(config)) {
            prop.load(input);
            username = prop.getProperty("username");
            password = prop.getProperty("password");
        }
    }

    @SuppressWarnings("unused")
    private class SkypeEventListener {
        public void onMessage(SkypeMessageReceivedEvent e) {
            Callback<String> callback;
            SkypeMessage received = e.getMessage();

            if ((callback = Resource.getCallback(received.getSender().getUsername())) != null) {
                callback.callback(received.getMessage());
                return;
            }

            StatisticsManager.instance().logMessage(received);
            ModuleManager.parseText(received);
        }
    }

    public static SkypeBot getInstance() {
        if (instance == null) {
            new SkypeBot(new String[]{});
        }

        return instance;
    }

    public EzSkype getEzSkype() {
        return skype;
    }

    public String askQuestion(String question) {
        if (bot == null) {
            return "ChatterBot Died";
        }

        try {
            return bot.think(question);
        } catch (Exception ignored) {
            return "I am overthinking... (" + ExceptionUtils.getStackTrace(ignored) + ")";
        }
    }

    public Connection getDatabase() {
        return database;
    }

    private SkypeConversation groupConv;

    public void sendMessage(String message) {
        if (groupConv == null) {
            for (SkypeConversation conv : skype.getConversations().values()) {
                if (conv.getConversationType() == SkypeConversationType.GROUP) {
                    groupConv = conv;
                    break;
                }
            }
        }
        groupConv.sendMessage(message);
    }

    public Twitter getTwitter() {
        return twitter;
    }

    public CooldownHandler getCooldownHandler() {
        return cooldownHandler;
    }
}
