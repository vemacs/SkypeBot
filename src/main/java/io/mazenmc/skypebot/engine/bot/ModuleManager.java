package io.mazenmc.skypebot.engine.bot;

import in.kyle.ezskypeezlife.api.obj.SkypeMessage;
import io.mazenmc.skypebot.SkypeBot;
import io.mazenmc.skypebot.utils.Resource;
import io.mazenmc.skypebot.utils.Utils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.reflections.Reflections;
import sun.reflect.MethodAccessor;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ModuleManager {

    private static HashMap<String, CommandData> allCommands = new HashMap<>();
    private static HashMap<String, CommandData> commandData = new HashMap<>();

    private static long lastCommand = 0L;

    private static void executeCommand(SkypeMessage chat, CommandData data, Matcher m) {
        if (data.getCommand().admin()) {
            try {
                if (!Arrays.asList(Resource.GROUP_ADMINS).contains(chat.getSender().getUsername())) {
                    Resource.sendMessage(chat, "Access Denied!");
                    return;
                }
            } catch (Exception ignored) {
                return;
            }
        }

        try {
            if (data.getCommand().cooldown() > 0 &&
                    !Arrays.asList(Resource.GROUP_ADMINS).contains(chat.getSender().getUsername())) {
                if (!SkypeBot.getInstance().getCooldownHandler().canUse(data.getCommand())) {
                    return;
                }
            }

            long difference = System.currentTimeMillis() - lastCommand;

            if (difference <= 5000L) {
                return;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        List<Object> a = new ArrayList<>();
        a.add(chat);

        if (m.groupCount() > 0) {
            for (int i = 1; i <= m.groupCount(); i++) {
                String g = m.group(i);
                if (g.contains(".") && Utils.isDouble(g)) {
                    a.add(Double.parseDouble(g));
                } else if (Utils.isInteger(g)) {
                    a.add(Integer.parseInt(g));
                } else {
                    a.add(g);
                }
            }
        }

        if (a.size() < data.getMethod().getParameterCount()) {
            for (int i = a.size(); i < data.getMethod().getParameterCount(); i++) {
                if (data.getMethod().getParameters()[i].getType().equals(String.class)) {
                    a.add(null);
                } else {
                    a.add(0);
                }
            }
        }

        MethodAccessor methodAccessor = null;
        try {
            Field methodAccessorField = Method.class.getDeclaredField("methodAccessor");
            methodAccessorField.setAccessible(true);
            methodAccessor = (MethodAccessor) methodAccessorField.get(data.getMethod());

            if (methodAccessor == null) {
                Method acquireMethodAccessorMethod = Method.class.getDeclaredMethod("acquireMethodAccessor", null);
                acquireMethodAccessorMethod.setAccessible(true);
                methodAccessor = (MethodAccessor) acquireMethodAccessorMethod.invoke(data.getMethod(), null);

                lastCommand = System.currentTimeMillis();
            }
        } catch (NoSuchFieldException | InvocationTargetException | IllegalAccessException | NoSuchMethodException e) {
            Resource.sendMessage(chat, "Failed... (" + ExceptionUtils.getStackTrace(e) + ")");
        }

        try {
            methodAccessor.invoke(null, a.toArray());
        } catch (Exception e) {
            Resource.sendMessage(chat, "Failed... (" + Utils.upload(ExceptionUtils.getStackTrace(e)) + ")");
        }

    }

    public static HashMap<String, CommandData> getCommands() {
        return commandData;
    }

    public static void loadModules(String modulePackage) {
        Reflections r = new Reflections(modulePackage);
        Set<Class<? extends Module>> classes = r.getSubTypesOf(Module.class);

        classes.forEach(ModuleManager::registerModule);
    }

    public static void registerModule(Class<? extends Module> c) {
        for (Method m : c.getMethods()) {
            Command command;
            command = m.getAnnotation(Command.class);

            if (command != null) {
                CommandData data = new CommandData(command, m);

                System.out.println("registered " + command.name());

                commandData.put(command.name(), data);
                allCommands.put(command.name(), data);
                if (command.alias() != null && command.alias().length > 0) {
                    for (String s : command.alias()) {
                        allCommands.put(s, data);
                    }
                }
            }
        }
    }

    public static void removeModule(Class<? extends Module> c) {
        for (Method m : c.getMethods()) {
            Command command;
            command = m.getAnnotation(Command.class);

            if (command != null) {
                System.out.println("unregistered " + command.name());

                commandData.remove(command.name());
                allCommands.remove(command.name());
                if (command.alias() != null && command.alias().length > 0) {
                    for (String s : command.alias()) {
                        allCommands.remove(s);
                    }
                }
            }
        }
    }

    public static void parseText(SkypeMessage chat) {
        String command;
        String originalCommand;
        try {
            command = chat.getMessage();
            originalCommand = command;
        } catch (Exception ignored) {
            System.out.println("Skype exception occurred");
            return;
        }

        if (command == null) {
            System.out.println("Command is null");
            return;
        }

        System.out.println("Received chat message: " + command);

        if (command.length() < 1) {
            System.out.println("low command length");
            return;
        }

        if (command.startsWith(Resource.COMMAND_PREFIX)) {
            command = command.substring(1);
        }

        String[] commandSplit = command.split(" ");

        if (commandSplit.length == 0) {
            System.out.println("nothing");
            return;
        }

        for (Map.Entry<String, CommandData> s : allCommands.entrySet()) {
            String match = s.getKey();
            if (!s.getValue().getParameterRegex(false).equals("")) {
                match += " " + s.getValue().getParameterRegex(false);
            }

            if (s.getValue().getCommand().command()) {
                match = Resource.COMMAND_PREFIX + match;
            }

            if (s.getValue().getCommand().exact()) {
                match = "^" + match + "$";
            }

            Pattern r = Pattern.compile(match);
            Matcher m = r.matcher(originalCommand);

            if (m.find()) {
                executeCommand(chat, s.getValue(), m);
                System.out.println("executed command");
                return;
            } else if (!s.getValue().getParameterRegex(false).equals(s.getValue().getParameterRegex(true))) {
                match = s.getKey();
                if (!s.getValue().getParameterRegex(true).equals("")) {
                    match += " " + s.getValue().getParameterRegex(true);
                }

                if (s.getValue().getCommand().command()) {
                    match = Resource.COMMAND_PREFIX + match;
                }

                if (s.getValue().getCommand().exact()) {
                    match = "^" + match + "$";
                }

                r = Pattern.compile(match);
                m = r.matcher(originalCommand);
                if (m.find()) {
                    executeCommand(chat, s.getValue(), m);
                    return;
                }
            }
        }

        if (allCommands.containsKey(commandSplit[0].toLowerCase())) {
            CommandData d = allCommands.get(commandSplit[0].toLowerCase());
            Command c = d.getCommand();

            String correct = commandSplit[0];
            if (!d.getParameterNames().equals("")) {
                correct += " " + d.getParameterNames();
            }

            if (c.command()) {
                if (!originalCommand.startsWith(Resource.COMMAND_PREFIX)) {
                    return;
                }

                correct = Resource.COMMAND_PREFIX + correct;
            }

            Resource.sendMessage(chat, "Incorrect syntax: " + correct);

            return;
        }
    }

}
