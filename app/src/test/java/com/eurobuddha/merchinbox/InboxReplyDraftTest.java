package com.eurobuddha.merchinbox;
import android.app.Activity;
import android.os.Bundle;
import android.os.Looper;
import android.widget.EditText;
import android.widget.LinearLayout;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.time.Duration;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.robolectric.Shadows.shadowOf;

/** Real EditText/selection and lifecycle Bundle behavior; no node or application startup. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class InboxReplyDraftTest {
    Activity activity;LinearLayout root;ReplyDrafts drafts;
    @Before public void setup(){activity=Robolectric.buildActivity(Activity.class).setup().get();root=new LinearLayout(activity);root.setFocusableInTouchMode(true);activity.setContentView(root);drafts=new ReplyDrafts();}
    @After public void close(){drafts.close();activity.finish();}
    EditText field(String ref,Runnable refresh){EditText e=new EditText(activity);root.addView(e);drafts.bind(ref,e,refresh);return e;}
    @Test public void focusedEditorSurvivesRefreshRequestsWithExactUnicodeSelectionAndComposition() {
        Runnable refresh=mock(Runnable.class);EditText e=field("one",refresh);e.setText("  中文 Привіт русский\n unsent  ");e.setSelection(2,7);e.requestFocus();
        android.text.Editable original=e.getText();Object composing=new Object();original.setSpan(composing,2,4,android.text.Spanned.SPAN_COMPOSING);
        for(int i=0;i<4;i++)assertTrue(drafts.defer("one"));
        assertSame(original,e.getText());assertEquals(2,e.getSelectionStart());assertEquals(7,e.getSelectionEnd());assertEquals(2,original.getSpanStart(composing));verifyNoInteractions(refresh);
        root.requestFocus();shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));verify(refresh).run();
    }
    @Test public void draftsStaySeparateAndRestoreThroughRebuildAndSavedInstanceState() {
        EditText first=field("one",()->{});first.setText("first draft");first.setSelection(2,5);
        EditText second=field("two",()->{});second.setText("друга чернетка");second.setSelection(3);
        EditText rebuilt=field("one",()->{});assertEquals("first draft",rebuilt.getText().toString());assertEquals(2,rebuilt.getSelectionStart());assertEquals(5,rebuilt.getSelectionEnd());
        Bundle state=new Bundle();drafts.save(state);drafts.close();drafts=new ReplyDrafts();drafts.restore(state);
        EditText restored=field("two",()->{});assertEquals("друга чернетка",restored.getText().toString());assertEquals(3,restored.getSelectionStart());
    }
    @Test public void failedSaveAndDuplicateClickKeepTheDraftAndAllowExplicitRetry() {
        EditText e=field("one",()->{});e.setText("  message  ");ReplyDrafts.Submission ticket=drafts.begin("one");assertNotNull(ticket);assertEquals("message",ticket.text());assertNull(drafts.begin("one"));
        drafts.failed(ticket);assertEquals("  message  ",e.getText().toString());assertNotNull(drafts.begin("one"));
    }
    @Test public void successfulSaveClearsOnlyItsOwnUnchangedDraft() {
        EditText e=field("one",()->{});e.setText("first");ReplyDrafts.Submission first=drafts.begin("one");e.setText("second");drafts.saved(first);assertEquals("second",e.getText().toString());
        ReplyDrafts.Submission second=drafts.begin("one");drafts.saved(first);assertNull(drafts.begin("one"));drafts.saved(second);assertEquals("",e.getText().toString());
    }
    @Test public void editingBackToTheSameTextStillProtectsTheNewDraftFromAnOldCallback() {
        EditText e=field("one",()->{});e.setText("same");ReplyDrafts.Submission ticket=drafts.begin("one");e.setText("new");e.setText("same");drafts.saved(ticket);assertEquals("same",e.getText().toString());
    }
    @Test public void closingTheHostSuppressesQueuedFocusCallbacksAndBlankDraftsDoNotSend() {
        Runnable refresh=mock(Runnable.class);EditText e=field("one",refresh);e.setText(" \n ");assertNull(drafts.begin("one"));e.requestFocus();assertTrue(drafts.defer("one"));root.requestFocus();drafts.close();shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));verifyNoInteractions(refresh);assertNull(drafts.begin("one"));
    }
}
