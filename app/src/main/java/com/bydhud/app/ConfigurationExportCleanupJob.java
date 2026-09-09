package com.bydhud.app;

import android.app.job.JobParameters;
import android.app.job.JobService;

public final class ConfigurationExportCleanupJob extends JobService {
    @Override public boolean onStartJob(JobParameters params) {
        ConfigurationExportArtifacts.checkAsync(this, () -> jobFinished(params, false));
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) { return true; }
}
