package com.example.lockdowndpc.guard;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.example.lockdowndpc.R;

/** Child-facing detail for one parent notice; never requires the administrator PIN. */
public final class GuardNoticeActivity extends AppCompatActivity {
    private LinearLayout root;
    private TextView status;
    private Button acknowledge;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        showNotice();
    }

    private void showNotice() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);

        String id = getIntent().getStringExtra(GuardNoticeCoordinator.EXTRA_NOTICE_ID);
        GuardNoticeStore.Notice notice = GuardNoticeStore.find(this, id);
        TextView title = new TextView(this);
        title.setTextSize(24);
        root.addView(title);
        status = new TextView(this);
        status.setTextSize(18);
        status.setPadding(0, 16, 0, 16);
        root.addView(status);
        if (notice == null) {
            title.setText(R.string.guard_notice_unavailable);
            addClose();
            return;
        }
        boolean callback = "callback".equals(notice.kind());
        title.setText(callback ? R.string.guard_notice_callback_title
                : R.string.guard_notice_task_title);
        status.setText(notice.message().isEmpty()
                ? getString(callback ? R.string.guard_notice_callback_body
                        : R.string.guard_notice_task_body)
                : notice.message());
        acknowledge = new Button(this);
        acknowledge.setText(R.string.guard_notice_ack);
        acknowledge.setOnClickListener(view -> acknowledge(notice.id()));
        root.addView(acknowledge);
        addClose();
    }

    private void acknowledge(String id) {
        if (acknowledge == null) return;
        acknowledge.setEnabled(false);
        acknowledge.setText(R.string.guard_notice_working);
        GuardNoticeCoordinator.acknowledgeAsync(this, id, () -> runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            GuardNoticeStore.Notice remaining = GuardNoticeStore.find(this, id);
            if (remaining == null) {
                finish();
                return;
            }
            acknowledge.setEnabled(true);
            acknowledge.setText(R.string.guard_notice_ack);
            status.setText(R.string.guard_notice_ack_failed);
        }));
    }

    private void addClose() {
        Button close = new Button(this);
        close.setText(R.string.guard_close);
        close.setOnClickListener(view -> finish());
        root.addView(close);
    }
}
