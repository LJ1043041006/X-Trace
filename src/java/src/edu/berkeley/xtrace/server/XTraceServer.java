/*
 * Copyright (c) 2005,2006,2007 The Regents of the University of California.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above copyright
 *       notice, this list of conditions and the following disclaimer in the
 *       documentation and/or other materials provided with the distribution.
 *     * Neither the name of the University of California, nor the
 *       names of its contributors may be used to endorse or promote products
 *       derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE UNIVERSITY OF CALIFORNIA ``AS IS'' AND ANY
 * EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE UNIVERSITY OF CALIFORNIA BE LIABLE FOR ANY
 * DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */


package edu.berkeley.xtrace.server;

import java.io.IOException;
import java.io.Writer;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.Logger;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.Velocity;
import org.apache.velocity.exception.MethodInvocationException;
import org.apache.velocity.exception.ParseErrorException;
import org.apache.velocity.exception.ResourceNotFoundException;
import org.mortbay.jetty.Server;
import org.mortbay.jetty.servlet.Context;
import org.mortbay.jetty.servlet.DefaultServlet;
import org.mortbay.jetty.servlet.ServletHolder;
import org.mortbay.servlet.CGI;

import edu.berkeley.xtrace.TaskID;
import edu.berkeley.xtrace.XTraceException;
import edu.berkeley.xtrace.reporting.Report;

/**
 * @author George Porter
 *
 */
public final class XTraceServer {
	private static final Logger LOG = Logger.getLogger(XTraceServer.class);

	private static ReportSource[] sources;
	
	private static BlockingQueue<String> incomingReportQueue, reportsToStorageQueue;

	private static ThreadPerTaskExecutor sourcesExecutor;

	private static ExecutorService storeExecutor;

	private static QueryableReportStore reportstore;
	
	private static final DateFormat JSON_DATE_FORMAT =
		new SimpleDateFormat("yyyy-MM-dd HH:mm:ss"); 
	
	public static void main(String[] args) {
		BasicConfigurator.configure();
		
		// If they use the default configuration (the FileTree report store),
		// then they have to specify the directory in which to store reports
		if (System.getProperty("xtrace.server.store") == null) {
			if (args.length < 1) {
				System.err.println("Usage: Main <dataDir>");
				System.exit(1);
			}
			System.setProperty("xtrace.server.storedirectory", args[0]);
		}
		
		setupReportSources();
		setupReportStore();
		setupBackplane();
		setupWebInterface();
	}

	private static void setupReportSources() {
		
		incomingReportQueue = new ArrayBlockingQueue<String>(1024, true);
		sourcesExecutor = new ThreadPerTaskExecutor();
		
		// Default input sources
		String sourcesStr = "edu.berkeley.xtrace.server.UdpReportSource," +
		                    "edu.berkeley.xtrace.server.TcpReportSource";
		
		if (System.getProperty("xtrace.server.sources") != null) {
			sourcesStr = System.getProperty("xtrace.server.sources");
		} else {
			LOG.warn("No server report sources specified... using defaults (Udp,Tcp)");
		}
		String[] sourcesLst = sourcesStr.split(",");
		
		sources = new ReportSource[sourcesLst.length];
		for (int i = 0; i < sourcesLst.length; i++) {
			try {
				sources[i] = (ReportSource) Class.forName(sourcesLst[i]).newInstance();
			} catch (InstantiationException e1) {
				LOG.fatal("Could not instantiate report source", e1);
				System.exit(-1);
			} catch (IllegalAccessException e1) {
				LOG.fatal("Could not access report source", e1);
				System.exit(-1);
			} catch (ClassNotFoundException e1) {
				LOG.fatal("Could not find report source class", e1);
				System.exit(-1);
			}
			sources[i].setReportQueue(incomingReportQueue);
			try {
				sources[i].initialize();
			} catch (XTraceException e) {
				LOG.warn("Unable to initialize report source", e);
				// TODO: gracefully shutdown any previously started threads?
				System.exit(-1);
			}
			sourcesExecutor.execute((Runnable) sources[i]);
		}
	}
	
	private static void setupReportStore() {
		reportsToStorageQueue = new ArrayBlockingQueue<String>(1024);
		
		String storeStr = "edu.berkeley.xtrace.server.FileTreeReportStore";
		if (System.getProperty("xtrace.server.store") != null) {
			storeStr = System.getProperty("xtrace.server.store");
		} else {
			LOG.warn("No server report store specified... using default (FileTreeReportStore)");
		}
		
		reportstore = null;
		try {
			reportstore = (QueryableReportStore) Class.forName(storeStr).newInstance();
		} catch (InstantiationException e1) {
			LOG.fatal("Could not instantiate report store", e1);
			System.exit(-1);
		} catch (IllegalAccessException e1) {
			LOG.fatal("Could not access report store class", e1);
			System.exit(-1);
		} catch (ClassNotFoundException e1) {
			LOG.fatal("Could not find report store class", e1);
			System.exit(-1);
		}
		
		reportstore.setReportQueue(reportsToStorageQueue);
		try {
			reportstore.initialize();
		} catch (XTraceException e) {
			LOG.fatal("Unable to start report store", e);
			System.exit(-1);
		}
		
		storeExecutor = Executors.newSingleThreadExecutor();
		storeExecutor.execute(reportstore);
		
		/* Every N seconds we should sync the report store */
		String syncIntervalStr = System.getProperty("xtrace.server.syncinterval", "5");
		long syncInterval = Integer.parseInt(syncIntervalStr);
		Timer timer= new Timer();
		timer.schedule(new SyncTimer(reportstore), syncInterval*1000, syncInterval*1000);
		
		/* Add a shutdown hook to flush and close the report store */
		Runtime.getRuntime().addShutdownHook(new Thread() {
		  public void run() {
			  reportstore.shutdown();
		  }
		});
	}
	
	private static void setupBackplane() {
		new Thread(new Runnable() {
			public void run() {
				LOG.info("Backplane waiting for packets");
				
				while (true) {
					String msg = null;
					try {
						msg = incomingReportQueue.take();
					} catch (InterruptedException e) {
						LOG.warn("Interrupted", e);
						continue;
					}
					reportsToStorageQueue.offer(msg);
				}
			}
		}).start();
	}
	
	private static class ThreadPerTaskExecutor implements Executor {
	     public void execute(Runnable r) {
	         new Thread(r).start();
	     }
	 }
	
	private static void setupWebInterface() {
		String webDir = System.getProperty("xtrace.backend.webui.dir");
		if (webDir == null) {
			LOG.warn("No webui directory specified... using default (./src/webui)");
			webDir = "./src/webui";
		}
    
    int httpPort =
      Integer.parseInt(System.getProperty("xtrace.backend.httpport", "8080"));

		// Initialize Velocity template engine
		try {
			Velocity.setProperty(Velocity.RUNTIME_LOG_LOGSYSTEM_CLASS,
					"org.apache.velocity.runtime.log.Log4JLogChute");
			Velocity.setProperty("runtime.log.logsystem.log4j.logger",
					"edu.berkeley.xtrace.server.Main");
			Velocity.setProperty("file.resource.loader.path", webDir + "/templates");
			Velocity.setProperty("file.resource.loader.cache", "true");
			Velocity.init();
		} catch (Exception e) {
			LOG.warn("Failed to initialize Velocity", e);
		}
		
		// Create Jetty server
    Server server = new Server(httpPort);
    Context context = new Context(server, "/");
    
    // Create a CGI servlet for scripts in webui/cgi-bin 
    ServletHolder cgiHolder = new ServletHolder(new CGI());
    cgiHolder.setInitParameter("cgibinResourceBase", webDir + "/cgi-bin");
    context.addServlet(cgiHolder, "*.cgi");
    context.addServlet(cgiHolder, "*.pl");
    context.addServlet(cgiHolder, "*.py");
    context.addServlet(cgiHolder, "*.rb");
    context.addServlet(cgiHolder, "*.tcl");

    context.addServlet(new ServletHolder(
        new GetReportsServlet()), "/reports/*");
    context.addServlet(new ServletHolder(
        new GetLatestTaskServlet()), "/latestTask");
    context.addServlet(new ServletHolder(
        new LatestTasksServlet()), "/latestTasks");
    context.addServlet(new ServletHolder(
        new TagServlet()), "/tag/*");
    context.addServlet(new ServletHolder(
        new TagJSONServlet()), "/tagjson/*");
    
    // Add an IndexServlet as the default servlet. This servlet will serve
    // a human-readable (HTML) latest tasks page for "/" and serve static
    // content for any other URL. Being the default servlet, it will get
    // invoked only for URLs that does not match the other patterns where we
    // have registered servlets above.
    context.setResourceBase(webDir + "/html");
    context.addServlet(new ServletHolder(new IndexServlet()), "/");
    
    try {
      server.start();
    } catch (Exception e) {
      LOG.warn("Unable to start web interface", e);
    }
	}
	
	private static class GetReportsServlet extends HttpServlet {
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
        throws ServletException, IOException {
      response.setContentType("text/plain");
      response.setStatus(HttpServletResponse.SC_OK);
      String uri = request.getRequestURI();
      int pathLen = request.getServletPath().length() + 1;
      String taskId = uri.length() > pathLen ? uri.substring(pathLen) : null;
      Writer out = response.getWriter();
      if (taskId != null) {
        Iterator<Report> iter;
        try {
          iter = reportstore.getReportsByTask(TaskID.createFromString(taskId));
        } catch (XTraceException e) {
          throw new ServletException(e);
        }
        while (iter.hasNext()) {
          out.write(iter.next().toString());
          out.write("\n");
        }
      }
    }
  }
	
	private static class GetLatestTaskServlet extends HttpServlet {
	  protected void doGet(HttpServletRequest request, HttpServletResponse response)
	      throws ServletException, IOException {
      response.setContentType("text/plain");
      response.setStatus(HttpServletResponse.SC_OK);
      Writer out = response.getWriter();
      
      List<TaskRecord> task = reportstore.getLatestTasks(1);
      if (task.size() != 1) {
        LOG.warn("getLatestTasks(1) returned " + task.size() + " entries");
        return;
      }
      try {
        Iterator<Report> iter = reportstore.getReportsByTask(task.get(0).getTaskId());
        while (iter.hasNext()) {
          Report r = iter.next();
          out.write(r.toString());
          out.write("\n");
        }
      } catch (XTraceException e) {
        LOG.warn("Internal error", e);
        out.write("Internal error: " + e);
      }
	  }
	}
  
  private static class LatestTasksServlet extends HttpServlet {
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
        throws ServletException, IOException {
    	Collection<TaskRecord> tasks = getLatestTasks(request);
      showTasksAsJson(response, tasks);
    }
  }
  
  private static class TagServlet extends HttpServlet {
	    protected void doGet(HttpServletRequest request, HttpServletResponse response)
	        throws ServletException, IOException {
	      String uri = request.getRequestURI();
	      int pathLen = request.getServletPath().length() + 1;
	      String tag = uri.length() > pathLen ? uri.substring(pathLen) : null;
	      if (tag == null || tag.equalsIgnoreCase("")) {
	      	response.sendError(505, "No tag given");
	      } else {
	      	Collection<TaskRecord> taskInfos = reportstore.getTasksByTag(tag);
	      	showTasksAsHtml(response, taskInfos, "Tasks with tag: " + tag, false);
	      }
	    }
	  }
  
  private static class TagJSONServlet extends HttpServlet {
	    protected void doGet(HttpServletRequest request, HttpServletResponse response)
	        throws ServletException, IOException {
	    	String uri = request.getRequestURI();
	      int pathLen = request.getServletPath().length() + 1;
	      String tag = uri.length() > pathLen ? uri.substring(pathLen) : null;
	      if (tag == null || tag.equalsIgnoreCase("")) {
		      showTasksAsJson(response, new LinkedList<TaskRecord>());
	      } else {
	      	showTasksAsJson(response, reportstore.getTasksByTag(tag));
	      }
	    }
	  }
  
  private static class IndexServlet extends DefaultServlet {
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
        throws ServletException, IOException {
      if(request.getRequestURI().equals("/")) {
        Collection<TaskRecord> tasks = getLatestTasks(request);
        showTasksAsHtml(response, tasks, "X-Trace Latest Tasks", true);
      } else {
        super.doGet(request, response);
      }
    }
  }

  private static Collection<TaskRecord> getLatestTasks(HttpServletRequest request)
      throws ServletException, IOException {
    long windowHours;
    try {
      windowHours = Long.parseLong(request.getParameter("window"));
      if (windowHours < 0) throw new IllegalArgumentException();
    } catch(Exception ex) {
      windowHours = 24;
    }
    long startTime = System.currentTimeMillis() - windowHours * 60 * 60 * 1000;
    return reportstore.getTasksSince(startTime);
  }

	private static void showTasksAsJson(HttpServletResponse response,
			Collection<TaskRecord> tasks) throws IOException {
		response.setContentType("text/plain");
    VelocityContext context = new VelocityContext();
    context.put("tasks", tasks);
    context.put("dateFormat", JSON_DATE_FORMAT);
    try {
			Velocity.mergeTemplate("tasks-json.vm", "UTF-8", context, response.getWriter());
      response.setStatus(HttpServletResponse.SC_OK);
		} catch (Exception e) {
			LOG.warn("Failed to display tasks-json.vm", e);
			response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
					"Failed to display tasks-json.vm");
		}
	}

	private static void showTasksAsHtml(HttpServletResponse response,
			Collection<TaskRecord> tasks, String title, boolean showDbStats) throws IOException {
		response.setContentType("text/html");
		VelocityContext context = new VelocityContext();
		context.put("tasks", tasks);
		context.put("title", title);
		context.put("reportStore", reportstore);
		context.put("showStats", showDbStats);
		try {
			Velocity.mergeTemplate("tasks-html.vm", "UTF-8", context, response.getWriter());
		  response.setStatus(HttpServletResponse.SC_OK);
		} catch (Exception e) {
			LOG.warn("Failed to display tasks-html.vm", e);
			response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
					"Failed to display tasks-html.vm");
		}
	}
  
	private static final class SyncTimer extends TimerTask {
		private QueryableReportStore reportstore;

		public SyncTimer(QueryableReportStore reportstore) {
			this.reportstore = reportstore;
		}

		public void run() {
			reportstore.sync();
		}
	}
}