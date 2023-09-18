package eu.unicore.ucc;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.cli.ParseException;

import eu.unicore.security.wsutil.client.authn.AuthenticationProvider;
import eu.unicore.ucc.helpers.ConsoleLogger;
import eu.unicore.ucc.helpers.EndProcessingException;
import eu.unicore.ucc.helpers.JLineLogger;
import eu.unicore.ucc.runner.Broker;
import eu.unicore.util.Log;

/**
 * UCC main class and entry point
 * 
 * @author schuller
 */
public class UCC{

	public static boolean unitTesting = false;

	public static boolean mute = false;

	public static final Map<String, Class<? extends Command>> cmds = new HashMap<>();

	public static final Map<String, AuthenticationProvider> authNMethods = 
			new HashMap<>();

	public static Integer exitCode=null;

	public static Command lastCommand=null;

	public static final NumberFormat numberFormat = NumberFormat.getInstance();

	private static final ConsoleLogger msg = new ConsoleLogger();

	public static String getVersion(){
		String v = UCC.class.getPackage().getSpecificationVersion();
		if(v==null)v="(DEVELOPMENT version)";
		return v+", https://www.unicore.eu";
	}

	public static final ExecutorService executor=new ThreadPoolExecutor(4,4,
			100,TimeUnit.MILLISECONDS,
			new LinkedBlockingQueue<>(),
			new ThreadFactory(){
		final AtomicInteger threadNumber = new AtomicInteger(1);
		public Thread newThread(Runnable r) {
			Thread t = new Thread(r);
			t.setName("ucc-executor-"+threadNumber.getAndIncrement());
			return t;
		}
	});

	static {
		try{
			ServiceLoader<ProvidedCommands> sl=ServiceLoader.load(ProvidedCommands.class);
			for(ProvidedCommands p: sl){
				try{
					Command[]commands=p.getCommands();
					for(Command cmd: commands){
						cmds.put(cmd.getName(), cmd.getClass());
					}
				}catch(Exception ex){
					msg.error("Could not load commands for provider "+p.getClass(), ex);
				}
			}
		}catch(Exception ex){
			msg.error("Could not load commands!", ex);
		}

		loadAuthNMethods();


		numberFormat.setMaximumFractionDigits(2);
	}

	//unit testing
	public static void loadAuthNMethods(){
		try{
			authNMethods.clear();
			ServiceLoader<AuthenticationProvider> authn=ServiceLoader.load(AuthenticationProvider.class);
			for(AuthenticationProvider p: authn){
				try{
					authNMethods.put(p.getName().toLowerCase(),p);
				}catch(Exception ex){
					msg.error("Problem with AuthN provider "+p.getClass(), ex);
				}
			}
		}catch(Exception ex){
			msg.error("Could not load AuthN options!", ex);
		}
	}

	public static void printUsage(boolean exit) {
		String version = getVersion();
		System.err.println("UCC " + version);
		System.err.println("Usage: ucc <command> [OPTIONS] <args>");
		System.err.println("The following commands are available:");
		List<Command>cmds = getAllCommands();
		Collections.sort(cmds, new Comparator<>() {
			public int compare(Command e1, Command e2) {
				return (e1.getCommandGroup()+e1.getName()).compareTo(
						e2.getCommandGroup()+e2.getName());
			}
		});
		String lastGroup = "";
		for (Command entry : cmds) {
			String group = entry.getCommandGroup();
			if (!group.equalsIgnoreCase(lastGroup)) {
				System.err.println(group + ":");
				lastGroup = group;
			}
			System.err.printf(" %-20s - %s", entry.getName(), entry.getDescription());
			System.err.println();
		}

		System.err.println("Enter 'ucc help-auth' for help on authentication options.");
		System.err.println("Enter 'ucc <command> -h' for help on a particular command.");

		if (exit && !unitTesting){
			System.exit(1);
		}
	}

	public static void printUsage() {
		printUsage(true);
	}

	public static void printAuthNUsage() {
		String version = getVersion();
		System.out.println("UCC " + version);
		System.out.println("Select the authentication method using the '-k', '--authenticationMethod' option.");
		System.out.println("The following authentication methods are available:");
		for (AuthenticationProvider entry : authNMethods.values()) {
			System.out.println();
			System.out.printf(" %-20s - %s", entry.getName(), entry.getDescription());
			System.out.println();
		}
		System.out.println();
		System.out.println("Enter 'ucc help-auth <authn-method>' for details.");
	}

	public static void printAuthNUsage(String authN) {
		if(authN == null){
			printAuthNUsage();
			return;
		}
		authN = authN.toLowerCase();
		String version = getVersion();
		System.out.println("UCC " + version);
		AuthenticationProvider entry = authNMethods.get(authN);
		if(entry == null){
			System.out.println("No such authentication method: "+authN);
		}
		else{
			System.out.println();
			System.out.printf(" %-20s - %s", entry.getName(), entry.getDescription());
			System.out.println();
			System.out.println();
			System.out.println(entry.getUsage());
			System.out.println();
		}
	}

	/**
	 * creates and initialises a UCC {@link Command} class
	 * 
	 * @param args - the arguments for the command, where the first argument is the command name
	 * @param shouldQuit - whether UCC should exit if no command could be found
	 *           
	 * @return an initialised {@link Command} object 
	 */
	public static Command initCommand(String[] args, boolean shouldQuit)
			throws ParseException {
		String command = args[0];
		Class<? extends Command> cmdClass = cmds.get(command);
		if (cmdClass == null) {
			System.err.println("No such command: '"+command+"'. See 'ucc help' for a list of commands.");
			if (shouldQuit && !unitTesting){
				System.exit(1);
			}
			return null;
		}
		Command cmd=getCommand(command);
		if(cmd!=null){
			try{
				cmd.init(args);
			} catch (ParseException pe) {
				cmd.printUsage();
				throw pe;
			}
		}
		return cmd;
	}


	public static Command getCommand(String name){
		Class<? extends Command> cmdClass = cmds.get(name);
		if(cmdClass==null)throw new IllegalArgumentException("No such command: "+name);
		try{
			return cmdClass.getConstructor().newInstance();
		}catch(Exception ex){
			throw new RuntimeException("Could not instantiate "+cmdClass.getName(),ex);
		}
	}

	public static List<Command>getAllCommands(){
		List<Command>result = new ArrayList<>();
		for(Class <? extends Command>c: cmds.values()){
			try{
				result.add(c.getConstructor().newInstance());
			}catch(Exception ex){
				msg.error("Could not instantiate "+c.getName(), ex);
			}
		}
		return result;
	}

	public static AuthenticationProvider getAuthNMethod(String name){
		String authN = name!=null?name.toLowerCase():null;
		AuthenticationProvider ret = authNMethods.get(authN);
		if (ret == null)
			throw new IllegalArgumentException("No such authentication method: " + name);
		return ret;
	}

	/**
	 * get the named broker implementation
	 * @param brokerName - broker to use. If <code>null</code>, the best broker will be used
	 * @param msg - message writer
	 */
	public static Broker getBroker(String brokerName){
		Broker broker=null;
		ServiceLoader<Broker>loader=ServiceLoader.load(Broker.class);
		Iterator<Broker>iter=loader.iterator();
		while(iter.hasNext()){
			try{
				Broker b=iter.next();
				if(b.getName().equalsIgnoreCase(brokerName)){
					broker = b;
					break;
				}
				else{
					if(broker==null){
						broker=b;
					}
					if(b.getPriority()>broker.getPriority())broker=b;
				}
			}catch(ServiceConfigurationError ex){
				msg.error("Could not load broker implementation", ex);
			}
		}
		if(broker!=null){
			msg.verbose("Using broker "+broker.getName());
		}	

		if(broker == null){
			throw new IllegalArgumentException("Broker '"+brokerName+"' cannot be found");
		}
		
		return broker;
	}
	
	public static String getBrokerList() {
		StringBuilder sb = new StringBuilder();
		ServiceLoader<Broker>loader=ServiceLoader.load(Broker.class);
		Iterator<Broker>iter=loader.iterator();
		while(iter.hasNext()) {
			if(sb.length()>0)sb.append(", ");
			sb.append(iter.next().getName());
		}
		return sb.toString();
	}

	/**
	 * Parses commandline args, initiates and runs action
	 */
	public static void main(String[] args) {
		JLineLogger.init();
		Command cmd = null;
		exitCode = null;
		lastCommand = null;
		try {
			boolean showHelp=args.length < 1 || isHelp(args[0]);
			boolean showAuthNHelp = args.length > 0  && args[0].equalsIgnoreCase("help-auth");
			boolean showVersion = args.length > 0  && isVersion(args[0]);
			if (showHelp) {
				printUsage(!unitTesting);
			}
			else if(showAuthNHelp){
				if(args.length == 1){
					printAuthNUsage();
				}
				else{
					String authN = args[1];
					printAuthNUsage(authN);
				}
			}else if(showVersion) {
				printVersion();
			}else{
				cmd = initCommand(args, !unitTesting);
				msg.setPrefix("[ucc "+cmd.getName()+"]");
				cmd.process();
				cmd.postProcess();
				lastCommand = cmd;
			}
			exitCode=0;
		} catch (EndProcessingException epe) {
			exitCode = epe.getExitCode();
		} catch (ParseException pe) {
			System.err.println("Error parsing commandline");
			pe.printStackTrace();
			exitCode = Constants.ERROR;
		} catch (Exception e) {
			if (cmd == null) {
				String msg=Log.createFaultMessage("Error setting up UCC command '"+args[0]+"'", e);
				System.err.println(msg);
			} else {
				cmd.error("An error occurred.", e);
			}
			exitCode = Constants.ERROR;
		}

		if (!unitTesting){
			System.exit(exitCode);	
		}

	}

	public static ConsoleLogger getConsoleLogger(){
		return msg;
	}

	private static String[] help = {"-?","?","-h","--help","help"};

	private static String[] version = {"-V","--version","-version","version"};

	private static boolean isHelp(String arg) {
		for(String h: help){
			if(arg.toLowerCase().equals(h))
				return true;
		}
		return false;
	}

	private static boolean isVersion(String arg) {
		for(String v: version){
			if(arg.equals(v))
				return true;
		}
		return false;
	}
	
	public static void printVersion() {
		System.err.println("UNICORE Commandline Client " + getVersion());
		System.err.println(System.getProperty("java.vm.name")+" "+System.getProperty("java.vm.version"));
		System.err.println("OS: "+System.getProperty("os.name")+" "+System.getProperty("os.version"));
	}
	
}
