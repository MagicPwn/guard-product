package com.example.lockdowndpc.guard;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.format.DateUtils;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.example.lockdowndpc.R;
import com.example.lockdowndpc.policy.AuditLog;
import com.example.lockdowndpc.policy.PolicyReconciliationCoordinator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Locale;

public final class GuardQuizActivity extends AppCompatActivity {
    private LinearLayout root;
    private TextView status;
    private TextView progress;
    private TextView taskReasonView;
    private TextView taskWaitView;
    private TextView parentReplyView;
    private EditText shortAnswer;
    private boolean working;
    private boolean answered;
    private boolean attemptInProgress;
    private List<QuizQuestion> questions = Collections.emptyList();
    private int questionIndex;
    private String target;
    private String requestId;
    private String triggerId;
    private String attemptId;
    private long quizVersion;
    private long policyRevision;
    private GuardPolicy taskPolicy;
    private String taskPackage;
    private GuardQuizRequestStore.Request taskRequest;
    private GuardPolicy.QuizTrigger taskTrigger;
    private GuardTaskContext.Reason taskReason;
    private GuardTaskContext.Wait taskWait;
    private int taskThresholdMinutes;
    private int taskBudgetMinutes;
    private final Handler waitHandler = new Handler(Looper.getMainLooper());
    private final Runnable waitTicker = new Runnable() {
        @Override
        public void run() {
            refreshWaitText();
            if (taskWait != null && taskWait.deadlineMillis() > 0L
                    && !isFinishing() && !isDestroyed()) {
                waitHandler.postDelayed(this, 1000L);
            }
        }
    };
    private final Set<Integer> selectedOptions = new LinkedHashSet<>();
    private final List<Boolean> correctness = new ArrayList<>();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
        enterFullScreen();
        handleIntent(getIntent());
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) enterFullScreen();
    }

    @Override
    protected void onDestroy() {
        waitHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (working || attemptInProgress) return;
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        String requested = intent == null ? null
                : intent.getStringExtra(GuardQuizRequestCoordinator.EXTRA_REQUEST_ID);
        if (requested == null || requested.isEmpty()) {
            requestId = null;
            showPackages();
            return;
        }
        requestId = requested;
        beginRequest(requested);
    }

    @SuppressWarnings("deprecation")
    private void enterFullScreen() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private void clearScreen() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
        progress = new TextView(this);
        progress.setTextSize(16);
        progress.setVisibility(View.GONE);
        root.addView(progress);
        taskReasonView = new TextView(this);
        taskReasonView.setTextSize(22);
        taskReasonView.setTypeface(taskReasonView.getTypeface(), android.graphics.Typeface.BOLD);
        taskReasonView.setVisibility(View.GONE);
        root.addView(taskReasonView);
        taskWaitView = new TextView(this);
        taskWaitView.setTextSize(17);
        taskWaitView.setPadding(0, 8, 0, 16);
        taskWaitView.setVisibility(View.GONE);
        root.addView(taskWaitView);
        parentReplyView = null;
        status = new TextView(this);
        status.setTextSize(20);
        root.addView(status);
        refreshTaskBanner();
    }

    private void setChooseContext(GuardPolicy policy) {
        taskPolicy = policy;
        taskPackage = null;
        taskRequest = null;
        taskTrigger = null;
        taskReason = GuardTaskContext.Reason.CHOOSE;
        taskWait = null;
        taskThresholdMinutes = 0;
        taskBudgetMinutes = 0;
        refreshTaskBanner();
    }

    private void setTaskContext(GuardPolicy policy, String packageName,
            GuardQuizRequestStore.Request request, GuardPolicy.QuizTrigger trigger,
            boolean inProgress) {
        taskPolicy = policy;
        taskPackage = packageName;
        taskRequest = request;
        taskTrigger = trigger;
        taskThresholdMinutes = trigger == null ? 0 : trigger.minutes();
        Integer budget = policy.dailyBudgets().get(packageName);
        taskBudgetMinutes = budget == null ? 0 : budget;
        refreshTaskState(inProgress);
    }

    private void refreshTaskState(boolean inProgress) {
        if (taskPolicy == null || taskPackage == null) return;
        boolean budgetExhausted = budgetExhausted(taskPolicy, taskPackage);
        taskReason = GuardTaskContext.reasonFor(budgetExhausted,
                taskRequest != null && taskRequest.manual(),
                taskTrigger == null ? null : taskTrigger.type());
        long now = System.currentTimeMillis();
        long rewardRemaining = 0L;
        Long expiry = GuardRewardStore.active(this).get(taskPackage);
        if (expiry != null) rewardRemaining = Math.max(0L, expiry - now);
        GuardTaskContext.Wait wait = GuardTaskContext.evaluateWait(
                eligible(taskPolicy, taskPackage, taskRequest), inProgress,
                rewardRemaining > 0L, rewardRemaining,
                QuizAttemptStore.countToday(this, taskPolicy.timezone()),
                taskPolicy.maxAttemptsPerDay(),
                QuizAttemptStore.cooldownRemaining(this, taskPolicy.cooldownSeconds()),
                taskPolicy.timezone(), now);
        taskWait = wait;
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            refreshTaskBanner();
        });
    }

    private boolean budgetExhausted(GuardPolicy policy, String packageName) {
        Integer budget = policy.dailyBudgets().get(packageName);
        if (budget == null) return false;
        Map<String, Long> usage = UsageLedger.today(this, policy.timezone());
        return usage.getOrDefault(packageName, 0L) >= budget * 60_000L;
    }

    private void refreshTaskBanner() {
        if (taskReasonView == null || taskWaitView == null) return;
        if (taskReason == null) {
            taskReasonView.setVisibility(View.GONE);
            taskWaitView.setVisibility(View.GONE);
            return;
        }
        taskReasonView.setText(reasonText());
        taskReasonView.setVisibility(View.VISIBLE);
        if (taskWait == null) {
            taskWaitView.setVisibility(View.GONE);
            return;
        }
        taskWaitView.setText(waitText(taskWait));
        taskWaitView.setVisibility(View.VISIBLE);
        waitHandler.removeCallbacks(waitTicker);
        if (taskWait.deadlineMillis() > 0L && !isFinishing() && !isDestroyed()) {
            waitHandler.postDelayed(waitTicker, 1000L);
        }
    }

    private void refreshWaitText() {
        if (taskWait == null || taskWaitView == null) return;
        GuardTaskContext.Wait current = GuardTaskContext.remaining(
                taskWait, System.currentTimeMillis());
        taskWait = current;
        taskWaitView.setText(waitText(current));
    }

    private CharSequence reasonText() {
        if (taskReason == null) return "";
        switch (taskReason) {
            case BUDGET_EXHAUSTED:
                return getString(R.string.guard_task_reason_budget,
                        taskPackage == null ? "" : taskPackage, taskBudgetMinutes);
            case TOTAL_USAGE:
                return getString(R.string.guard_task_reason_total_usage, taskThresholdMinutes);
            case CONTINUOUS_USE:
                return getString(R.string.guard_task_reason_continuous,
                        taskPackage == null ? "" : taskPackage, taskThresholdMinutes);
            case APP_ENTER:
                return getString(R.string.guard_task_reason_app_enter,
                        taskPackage == null ? "" : taskPackage);
            case SCHEDULE_START:
                return getString(R.string.guard_task_reason_schedule_start,
                        taskPackage == null ? "" : taskPackage);
            case MANUAL:
                return getString(R.string.guard_task_reason_manual,
                        taskPackage == null ? "" : taskPackage);
            case CHOOSE:
                return getString(R.string.guard_task_reason_choose);
            default:
                return getString(R.string.guard_task_reason_unavailable);
        }
    }

    private CharSequence waitText(GuardTaskContext.Wait wait) {
        long seconds = GuardTaskContext.remainingSecondsCeil(wait.remainingMillis());
        String elapsed = DateUtils.formatElapsedTime(Math.max(0L, seconds));
        switch (wait.kind()) {
            case COOLDOWN:
                return getString(R.string.guard_task_wait_cooldown, elapsed);
            case DAILY_LIMIT:
                return getString(R.string.guard_task_wait_daily_limit, elapsed);
            case REWARD_ACTIVE:
                return getString(R.string.guard_task_wait_reward, elapsed);
            case IN_PROGRESS:
                return getString(R.string.guard_task_wait_in_progress);
            case READY:
                return getString(R.string.guard_task_wait_ready);
            default:
                return getString(R.string.guard_task_wait_unavailable);
        }
    }

    private void addCloseButton() {
        Button close = new Button(this);
        close.setText(R.string.guard_close);
        close.setOnClickListener(view -> finish());
        root.addView(close);
    }

    private void addHelpSection() {
        if (taskPolicy == null) return;
        TextView heading = new TextView(this);
        heading.setText(R.string.guard_task_emergency_contacts);
        heading.setTextSize(18);
        heading.setTypeface(heading.getTypeface(), android.graphics.Typeface.BOLD);
        heading.setPadding(0, 24, 0, 8);
        root.addView(heading);

        Button help = new Button(this);
        help.setText(R.string.guard_task_help);
        help.setOnClickListener(view -> showHelpDialog());
        root.addView(help);

        Button sendHelp = new Button(this);
        sendHelp.setText(R.string.guard_task_send_help);
        sendHelp.setOnClickListener(view -> showSendHelpDialog());
        root.addView(sendHelp);

        Button checkReplies = new Button(this);
        checkReplies.setText(R.string.guard_task_check_replies);
        checkReplies.setOnClickListener(view -> refreshHelpReplies(true));
        root.addView(checkReplies);

        parentReplyView = new TextView(this);
        parentReplyView.setTextSize(17);
        parentReplyView.setPadding(0, 12, 0, 12);
        parentReplyView.setVisibility(View.GONE);
        root.addView(parentReplyView);

        List<GuardPolicy.HelpContact> contacts = taskPolicy.helpContacts();
        if (contacts.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.guard_task_no_contacts);
            root.addView(empty);
        } else {
            for (GuardPolicy.HelpContact contact : contacts) {
                Button call = new Button(this);
                call.setText(getString(R.string.guard_task_contact_button,
                        contact.label(), contact.phone()));
                call.setOnClickListener(view -> dial(contact.phone()));
                root.addView(call);
            }
        }
        TextView note = new TextView(this);
        note.setText(R.string.guard_task_contact_note);
        note.setTextSize(13);
        root.addView(note);
        refreshHelpReplies(false);
    }

    private void showHelpDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.guard_task_help_title)
                .setMessage(R.string.guard_task_help_message)
                .setPositiveButton(R.string.guard_close, null)
                .show();
    }

    private void showSendHelpDialog() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(24, 8, 24, 0);
        TextView explanation = new TextView(this);
        explanation.setText(R.string.guard_task_send_help_message);
        content.addView(explanation);
        EditText message = new EditText(this);
        message.setHint(R.string.guard_task_help_message_optional);
        message.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        content.addView(message);
        new AlertDialog.Builder(this)
                .setTitle(R.string.guard_task_send_help_title)
                .setView(content)
                .setNegativeButton(R.string.guard_close, null)
                .setPositiveButton(R.string.guard_task_help_send,
                        (dialog, which) -> sendHelpRequest(message.getText().toString()))
                .show();
    }

    private void sendHelpRequest(String message) {
        String reason = taskReason == null ? "blocked"
                : taskReason.name().toLowerCase(Locale.ROOT);
        String packageName = taskPackage;
        String requestTrigger = triggerId;
        new Thread(() -> {
            try {
                GuardHelpCoordinator.Result result = GuardHelpCoordinator.sendNow(
                        this, packageName, requestTrigger, reason, message);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    Toast.makeText(this, result.sent
                                    ? R.string.guard_task_help_sent
                                    : R.string.guard_task_help_queued,
                            Toast.LENGTH_LONG).show();
                    refreshHelpReplies(true);
                });
            } catch (IllegalStateException exception) {
                boolean limited = exception.getMessage() != null
                        && exception.getMessage().toLowerCase(Locale.ROOT).contains("too many");
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    Toast.makeText(this, limited ? R.string.guard_task_help_rate_limited
                            : R.string.guard_task_help_failed, Toast.LENGTH_LONG).show();
                });
            } catch (RuntimeException exception) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    Toast.makeText(this, R.string.guard_task_help_failed,
                            Toast.LENGTH_LONG).show();
                });
            }
        }, "guard-help-send").start();
    }

    private void refreshHelpReplies(boolean pollServer) {
        String packageName = taskPackage;
        new Thread(() -> {
            try {
                if (pollServer) GuardHelpCoordinator.poll(this);
            } catch (Exception ignored) {
            }
            java.util.List<GuardHelpRequestStore.Reply> replies =
                    GuardHelpRequestStore.unreadReplies(this, packageName);
            for (GuardHelpRequestStore.Reply reply : replies) {
                GuardHelpCoordinator.acknowledge(this, reply.requestId);
            }
            runOnUiThread(() -> {
                if (parentReplyView == null || isFinishing() || isDestroyed()) return;
                if (replies.isEmpty()) {
                    parentReplyView.setVisibility(View.GONE);
                    return;
                }
                StringBuilder text = new StringBuilder(getString(
                        R.string.guard_task_parent_reply_heading));
                for (GuardHelpRequestStore.Reply reply : replies) {
                    text.append("\n\n").append(getString(R.string.guard_task_parent_reply,
                            reply.parentReply));
                }
                parentReplyView.setText(text);
                parentReplyView.setVisibility(View.VISIBLE);
            });
        }, "guard-help-replies").start();
    }

    private void dial(String phone) {
        try {
            startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + phone)));
        } catch (RuntimeException exception) {
            Toast.makeText(this, R.string.guard_task_dial_unavailable,
                    Toast.LENGTH_LONG).show();
        }
    }

    private void showPackages() {
        attemptInProgress = false;
        clearScreen();
        status.setText(R.string.guard_quiz_intro);
        try {
            GuardPolicy policy = GuardPolicyStore.read(this);
            setChooseContext(policy);
            if (!policy.enabled() || !policy.quizGateEnabled()) {
                taskReason = GuardTaskContext.Reason.UNAVAILABLE;
                refreshTaskBanner();
                status.setText(R.string.guard_quiz_unavailable);
                addHelpSection();
                addCloseButton();
                return;
            }
            int choices = 0;
            for (String packageName : policy.dailyBudgets().keySet()) {
                if (policy.blockedPackages().contains(packageName)
                        || policy.isAlwaysAllowed(packageName)) continue;
                Button choice = new Button(this);
                choice.setText(packageName);
                choice.setOnClickListener(view -> begin(packageName));
                root.addView(choice);
                choices++;
            }
            if (choices == 0) status.setText(R.string.guard_quiz_unavailable);
            addHelpSection();
            addCloseButton();
        } catch (RuntimeException exception) {
            status.setText(R.string.guard_degraded);
            taskReason = GuardTaskContext.Reason.UNAVAILABLE;
            refreshTaskBanner();
            addHelpSection();
            addCloseButton();
        }
    }

    private void begin(String packageName) {
        beginAttempt(packageName, null);
    }

    private void beginRequest(String requested) {
        beginAttempt(null, requested);
    }

    private void beginAttempt(String requestedPackage, String requestedRequestId) {
        if (working) return;
        working = true;
        clearScreen();
        status.setText(R.string.guard_quiz_intro);
        PolicyReconciliationCoordinator.runOnPolicyThread(() -> {
            try {
                GuardPolicy policy = GuardPolicyStore.read(this);
                GuardQuizRequestStore.Request request = requestedRequestId == null ? null
                        : GuardQuizRequestStore.findActive(this, requestedRequestId);
                if (requestedRequestId != null && request == null) {
                    showMessage(R.string.guard_quiz_unavailable);
                    return;
                }
                String packageName = request == null
                        ? requestedPackage : request.targetPackage();
                if (packageName == null) {
                    showMessage(R.string.guard_quiz_unavailable);
                    return;
                }
                GuardPolicy.QuizTrigger trigger = request == null
                        || request.manual() || request.budgetExhausted()
                        ? null : findTrigger(policy, request.triggerId());
                setTaskContext(policy, packageName, request, trigger,
                        request != null && request.started());
                if (!eligible(policy, packageName, request)) {
                    if (request != null) GuardQuizRequestCoordinator.cancel(this, request.id());
                    showMessage(R.string.guard_quiz_unavailable);
                    return;
                }
                if (GuardRewardStore.isActive(this, packageName)) {
                    if (request != null) GuardQuizRequestCoordinator.cancel(this, request.id());
                    refreshTaskState(false);
                    showMessage(R.string.guard_task_reward_active);
                    return;
                }
                List<QuizQuestion> selected = QuizPackStore.questionsFor(this, policy);
                if (selected.isEmpty()) {
                    showMessage(R.string.guard_quiz_unavailable);
                    return;
                }
                String nextAttemptId;
                if (request != null && request.started()) {
                    nextAttemptId = request.attemptId();
                    if (nextAttemptId.isEmpty()) {
                        showMessage(R.string.guard_degraded);
                        return;
                    }
                } else {
                    if (!QuizAttemptStore.canBegin(this, policy)) {
                        refreshTaskState(false);
                        showMessage(R.string.guard_task_wait_message, () -> {
                            if (request != null) beginRequest(request.id());
                            else begin(packageName);
                        });
                        return;
                    }
                    if (!QuizAttemptStore.beginAttempt(this, policy)) {
                        refreshTaskState(false);
                        showMessage(R.string.guard_task_wait_message);
                        return;
                    }
                    nextAttemptId = "attempt-" + UUID.randomUUID();
                    if (request != null && !GuardQuizRequestStore.markStarted(this, request.id(),
                            nextAttemptId, policy.revision())) {
                        showMessage(R.string.guard_degraded);
                        return;
                    }
                }
                final String nextTarget = packageName;
                final String nextTrigger = request == null ? null : request.triggerId();
                final long nextRevision = policy.revision();
                final long nextQuizVersion = QuizPackStore.activeVersion(this);
                runOnUiThread(() -> {
                    working = false;
                    if (isFinishing() || isDestroyed()) return;
                    target = nextTarget;
                    triggerId = nextTrigger;
                    attemptId = nextAttemptId;
                    policyRevision = nextRevision;
                    quizVersion = nextQuizVersion;
                    requestId = request == null ? null : request.id();
                    questions = new ArrayList<>(selected);
                    questionIndex = 0;
                    answered = false;
                    correctness.clear();
                    attemptInProgress = true;
                    refreshTaskState(true);
                    showQuestion();
                });
            } catch (RuntimeException exception) {
                showMessage(R.string.guard_degraded);
            }
        });
    }

    private boolean eligible(GuardPolicy policy, String packageName,
            GuardQuizRequestStore.Request request) {
        if (!policy.enabled() || !policy.quizGateEnabled() || policy.rewardMinutes() <= 0
                || policy.isAlwaysAllowed(packageName)
                || policy.blockedPackages().contains(packageName)
                || GuardPolicyEnforcer.protectedPackages(this).contains(packageName)
                || !UsageLedger.hasUsageAccess(this)) return false;
        boolean managed = policy.dailyBudgets().containsKey(packageName)
                || policy.schedules().containsKey(packageName);
        if (!managed) return false;
        if (request != null) {
            if (request.policyRevision() != policy.revision()) return false;
            if (request.budgetExhausted()) {
                Integer budget = policy.dailyBudgets().get(packageName);
                if (budget == null) return false;
                Map<String, Long> usage = UsageLedger.today(this, policy.timezone());
                return usage.getOrDefault(packageName, 0L) >= budget * 60_000L;
            }
            if (request.manual()) return policy.managesPackage(packageName);
            GuardPolicy.QuizTrigger trigger = findTrigger(policy, request.triggerId());
            return trigger != null && trigger.packages().contains(packageName);
        }
        if (!policy.dailyBudgets().containsKey(packageName)) return false;
        Map<String, Long> usage = UsageLedger.today(this, policy.timezone());
        return usage.getOrDefault(packageName, 0L) >= policy.dailyBudgets().get(packageName) * 60_000L;
    }

    private static GuardPolicy.QuizTrigger findTrigger(GuardPolicy policy, String triggerId) {
        for (GuardPolicy.QuizTrigger trigger : policy.quizTriggers()) {
            if (trigger.id().equals(triggerId)) return trigger;
        }
        return null;
    }

    private void showQuestion() {
        if (questionIndex < 0 || questionIndex >= questions.size()) {
            showMessage(R.string.guard_degraded);
            return;
        }
        clearScreen();
        progress.setText(getString(R.string.guard_quiz_progress,
                questionIndex + 1, questions.size()));
        progress.setVisibility(View.VISIBLE);
        selectedOptions.clear();
        shortAnswer = null;

        QuizQuestion question = questions.get(questionIndex);
        status.setText(question.prompt());
        addImage(question);
        if (question.type() == QuizQuestion.Type.SINGLE_CHOICE) {
            showSingleChoice(question);
        } else if (question.type() == QuizQuestion.Type.MULTIPLE_CHOICE) {
            showMultipleChoice(question);
        } else if (question.type() == QuizQuestion.Type.TRUE_FALSE) {
            showTrueFalse();
        } else {
            showShortAnswer();
        }
        addHelpSection();
        addCloseButton();
    }

    private void addImage(QuizQuestion question) {
        if (!question.hasImage()) return;
        byte[] data = question.imageData();
        Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
        if (bitmap == null) return;
        ImageView image = new ImageView(this);
        image.setAdjustViewBounds(true);
        image.setContentDescription(getString(R.string.guard_quiz_image_description));
        image.setImageBitmap(bitmap);
        root.addView(image, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private void showSingleChoice(QuizQuestion question) {
        for (int index = 0; index < question.options().size(); index++) {
            final int answer = index;
            Button option = new Button(this);
            option.setText(question.options().get(index));
            option.setOnClickListener(view -> submitSelection(Collections.singleton(answer)));
            root.addView(option);
        }
    }

    private void showMultipleChoice(QuizQuestion question) {
        TextView hint = new TextView(this);
        hint.setText(R.string.guard_quiz_multiple_hint);
        root.addView(hint);
        for (int index = 0; index < question.options().size(); index++) {
            final int answer = index;
            CheckBox option = new CheckBox(this);
            option.setText(question.options().get(index));
            option.setOnCheckedChangeListener((button, checked) -> {
                if (checked) selectedOptions.add(answer);
                else selectedOptions.remove(answer);
            });
            root.addView(option);
        }
        Button submit = new Button(this);
        submit.setText(R.string.guard_quiz_submit);
        submit.setOnClickListener(view -> submitSelection(new HashSet<>(selectedOptions)));
        root.addView(submit);
    }

    private void showTrueFalse() {
        Button yes = new Button(this);
        yes.setText(R.string.guard_quiz_true);
        yes.setOnClickListener(view -> submitBoolean(true));
        root.addView(yes);
        Button no = new Button(this);
        no.setText(R.string.guard_quiz_false);
        no.setOnClickListener(view -> submitBoolean(false));
        root.addView(no);
    }

    private void showShortAnswer() {
        shortAnswer = new EditText(this);
        shortAnswer.setHint(R.string.guard_quiz_short_hint);
        shortAnswer.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        root.addView(shortAnswer);
        Button submit = new Button(this);
        submit.setText(R.string.guard_quiz_submit);
        submit.setOnClickListener(view -> submitText(shortAnswer.getText().toString()));
        root.addView(submit);
    }

    private void submitSelection(Set<Integer> answer) {
        if (answered) return;
        submitAnswer(questions.get(questionIndex).acceptsSelection(answer));
    }

    private void submitBoolean(boolean answer) {
        if (answered) return;
        submitAnswer(questions.get(questionIndex).acceptsBoolean(answer));
    }

    private void submitText(String answer) {
        if (answered) return;
        submitAnswer(questions.get(questionIndex).acceptsText(answer));
    }

    private void submitAnswer(boolean correct) {
        if (working || answered || questions.isEmpty()) return;
        answered = true;
        working = true;
        PolicyReconciliationCoordinator.runOnPolicyThread(() -> {
            try {
                GuardPolicy policy = GuardPolicyStore.read(this);
                GuardQuizRequestStore.Request request = requestId == null ? null
                        : GuardQuizRequestStore.findActive(this, requestId);
                if (policy.revision() != policyRevision || !eligible(policy, target, request)) {
                    showMessage(R.string.guard_quiz_unavailable);
                    return;
                }
                correctness.add(correct);
                if (!correct) {
                    QuizAttemptStore.recordWrong(this);
                    AuditLog.append(this, "guard-quiz-wrong:" + target);
                    submitResult(false);
                    finishRequest();
                    refreshTaskState(false);
                    showMessage(R.string.guard_quiz_wrong);
                    return;
                }
                if (questionIndex + 1 < questions.size()) {
                    runOnUiThread(() -> {
                        working = false;
                        answered = false;
                        if (isFinishing() || isDestroyed()) return;
                        questionIndex++;
                        showQuestion();
                    });
                    return;
                }
                if (!GuardRewardStore.grant(this, target, policy.rewardMinutes() * 60_000L)) {
                    submitResult(false);
                    finishRequest();
                    showMessage(R.string.guard_degraded);
                    return;
                }
                GuardScheduler.schedule(this);
                GuardPolicyEnforcer.Result result = GuardPolicyEnforcer.enforce(this);
                if (!result.verified()) {
                    GuardRewardStore.revoke(this, target);
                    GuardPolicyEnforcer.enforce(this);
                    submitResult(false);
                    finishRequest();
                    showMessage(R.string.guard_degraded);
                    return;
                }
                AuditLog.append(this, "guard-quiz-reward:" + target);
                submitResult(true);
                finishRequest();
                refreshTaskState(false);
                runOnUiThread(() -> {
                    attemptInProgress = false;
                    working = false;
                    if (isFinishing() || isDestroyed()) return;
                    clearScreen();
                    status.setText(R.string.guard_quiz_correct);
                    Button launch = new Button(this);
                    launch.setText(R.string.guard_launch_app);
                    launch.setOnClickListener(view -> launchRewardedApp());
                    root.addView(launch);
                    addHelpSection();
                    addCloseButton();
                });
            } catch (RuntimeException exception) {
                showMessage(R.string.guard_degraded);
            }
        });
    }

    private void submitResult(boolean rewardGranted) {
        if (attemptId == null || questions.isEmpty() || correctness.isEmpty()) return;
        int answeredCount = Math.min(questions.size(), correctness.size());
        List<QuizQuestion> answered = new ArrayList<>(
                questions.subList(0, answeredCount));
        List<Boolean> outcomes = new ArrayList<>(
                correctness.subList(0, answeredCount));
        try {
            org.json.JSONObject payload = QuizResultPayload.create(attemptId,
                    System.currentTimeMillis(), target, triggerId, policyRevision, quizVersion,
                    rewardGranted, answered, outcomes);
            if (!GuardQuizResultOutbox.enqueue(this, payload)) {
                AuditLog.append(this, "guard-quiz-result-persist-failed:" + target);
                return;
            }
            GuardQuizResultOutbox.flushAsync(this);
        } catch (org.json.JSONException | RuntimeException exception) {
            AuditLog.append(this, "guard-quiz-result-build-failed:"
                    + exception.getClass().getSimpleName());
        }
    }

    private void finishRequest() {
        if (requestId == null) return;
        GuardQuizRequestCoordinator.cancel(this, requestId);
        requestId = null;
    }

    private void launchRewardedApp() {
        try {
            if (!GuardRewardStore.isActive(this, target)) {
                status.setText(R.string.guard_quiz_unavailable);
                return;
            }
            Intent launch = getPackageManager().getLaunchIntentForPackage(target);
            if (launch == null) status.setText(R.string.guard_degraded);
            else startActivity(launch);
        } catch (RuntimeException exception) {
            status.setText(R.string.guard_degraded);
        }
    }

    private void showMessage(int message) {
        showMessage(message, this::showPackages);
    }

    private void showMessage(int message, Runnable retryAction) {
        runOnUiThread(() -> {
            attemptInProgress = false;
            working = false;
            if (isFinishing() || isDestroyed()) return;
            clearScreen();
            status.setText(message);
            Button retry = new Button(this);
            retry.setText(R.string.guard_quiz_retry);
            retry.setOnClickListener(view -> retryAction.run());
            root.addView(retry);
            addHelpSection();
            addCloseButton();
        });
    }
}
