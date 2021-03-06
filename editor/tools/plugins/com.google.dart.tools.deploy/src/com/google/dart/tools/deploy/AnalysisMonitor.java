package com.google.dart.tools.deploy;

import com.google.dart.tools.core.analysis.AnalysisEvent;
import com.google.dart.tools.core.analysis.AnalysisListener;
import com.google.dart.tools.core.analysis.AnalysisServer;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;

/**
 * Let the user know when analysis is occurring in the background.
 */
public class AnalysisMonitor {

  /**
   * Listen for the {@link AnalysisServer} idle state
   */
  private class Listener implements AnalysisListener {
    @Override
    public void idle(boolean idle) {
      synchronized (lock) {
        if (idle) {
          lock.notifyAll();
        } else if (job == null) {
          job = new NotificationJob();
          job.schedule(500);
        }
      }
    }

    @Override
    public void parsed(AnalysisEvent event) {
    }

    @Override
    public void resolved(AnalysisEvent event) {
    }
  }

  /**
   * Job that lets the user know when background analysis is occurring
   */
  private class NotificationJob extends Job {
    private NotificationJob() {
      super("Background Analysis");
    }

    @Override
    protected IStatus run(IProgressMonitor monitor) {
      monitor.beginTask("Analysis", IProgressMonitor.UNKNOWN);
      setName("Analyzing...");
      synchronized (lock) {
        while (!server.isIdle()) {
          try {
            lock.wait();
          } catch (InterruptedException e) {
            //$FALL-THROUGH$
          }
        }
        job = null;
      }
      monitor.done();
      return Status.OK_STATUS;
    }
  }

  // Lock on this field before accessing other fields
  private final Object lock = new Object();
  private AnalysisServer server;
  private Job job;

  public AnalysisMonitor(AnalysisServer server) {
    this.server = server;
  }

  public void start() {
    synchronized (lock) {
      job = new NotificationJob();
      job.schedule(3000);
      server.addAnalysisListener(new Listener());
    }
  }

}
