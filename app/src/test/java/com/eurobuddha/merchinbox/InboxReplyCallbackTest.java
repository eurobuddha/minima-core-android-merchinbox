package com.eurobuddha.merchinbox;
import android.app.Activity;
import android.os.Handler;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;
import com.eurobuddha.comms.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import org.junit.*;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class InboxReplyCallbackTest {
    Activity host;MainActivity activity;MerchDb db;ReplyDrafts drafts;EditText editor;MerchDb.Order order;
    ArrayDeque<Runnable> writes,updates;ArrayDeque<View> stack;MockedStatic<Toast> toasts;MockedStatic<CommsTransport> transport;
    @Before public void setup()throws Exception {
        host=Robolectric.buildActivity(Activity.class).setup().get();LinearLayout root=new LinearLayout(host);root.setFocusableInTouchMode(true);host.setContentView(root);editor=new EditText(host);root.addView(editor);
        activity=mock(MainActivity.class,CALLS_REAL_METHODS);
        doReturn(false).when(activity).isFinishing();doReturn(false).when(activity).isDestroyed();
        db=mock(MerchDb.class);drafts=new ReplyDrafts();drafts.bind("one",editor,()->{});editor.setText("reply");
        writes=new ArrayDeque<>();updates=new ArrayDeque<>();stack=new ArrayDeque<>();View list=new View(host);list.setTag("list");stack.push(list);View other=new View(host);other.setTag("order:two");stack.push(other);
        ExecutorService io=mock(ExecutorService.class);Handler ui=mock(Handler.class);doAnswer(c->{writes.add(c.getArgument(0));return null;}).when(io).execute(any(Runnable.class));when(ui.post(any(Runnable.class))).thenAnswer(c->{updates.add(c.getArgument(0));return true;});
        for(Object[] pair:new Object[][]{{"db",db},{"replyDrafts",drafts},{"io",io},{"ui",ui},{"stack",stack},{"node",mock(NodeApi.class)},{"crypto",mock(CryptoProvider.class)},{"myId","public-fixture"}}){Field f=MainActivity.class.getDeclaredField((String)pair[0]);f.setAccessible(true);f.set(activity,pair[1]);}
        order=new MerchDb.Order();order.ref="one";order.counterparty="recipient-fixture";
        toasts=mockStatic(Toast.class);toasts.when(()->Toast.makeText(any(android.content.Context.class),any(CharSequence.class),anyInt())).thenReturn(mock(Toast.class));transport=mockStatic(CommsTransport.class);
    }
    @After public void close(){transport.close();toasts.close();drafts.close();host.finish();}
    void invoke(String name,Class<?>[] types,Object... args)throws Exception{Method m=MainActivity.class.getDeclaredMethod(name,types);m.setAccessible(true);m.invoke(activity,args);}
    void send()throws Exception{invoke("sendReply",new Class<?>[]{MerchDb.Order.class,ReplyDrafts.Submission.class},order,drafts.begin("one"));}
    void drain(){while(!writes.isEmpty()||!updates.isEmpty()){if(!writes.isEmpty())writes.remove().run();if(!updates.isEmpty())updates.remove().run();}}
    @Test public void focusedReplyBlocksActualOrderRefreshBeforeRebuildingTheTree()throws Exception {
        stack.peek().setTag("order:one");editor.requestFocus();View before=stack.peek();invoke("refreshOrderIfTopmost",new Class<?>[]{String.class},"one");assertSame(before,stack.peek());assertEquals("reply",editor.getText().toString());verifyNoInteractions(db);
    }
    @Test public void failedLocalSaveDoesNotSendOrClearAndLateSuccessCannotHijackAnotherOrder()throws Exception {
        send();drain();assertEquals("reply",editor.getText().toString());transport.verifyNoInteractions();
        when(db.insertChat(anyString(),eq(false),anyString(),anyString(),anyLong())).thenReturn(true);send();drain();assertEquals("",editor.getText().toString());assertEquals("order:two",stack.peek().getTag());assertEquals(2,stack.size());
        transport.verify(()->CommsTransport.sendMessage(any(NodeApi.class),any(CryptoProvider.class),eq("recipient-fixture"),any(byte[].class),any(CommsTransport.SendCb.class)));
    }
    @Test public void newerDraftSurvivesSuccessfulWriteCallback()throws Exception {
        when(db.insertChat(anyString(),eq(false),anyString(),anyString(),anyLong())).thenReturn(true);send();editor.setText("next reply");drain();assertEquals("next reply",editor.getText().toString());assertEquals("order:two",stack.peek().getTag());
    }
}
