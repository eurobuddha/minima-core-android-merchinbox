package com.eurobuddha.merchinbox;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;
import java.util.*;

/** AtomiX's raw-draft/focus guard, keyed by order. User text is never a language binding. */
final class ReplyDrafts {
    private static final String STATE="mall.replyDrafts";
    private static final class Draft {
        String text="";int start,end;long revision;EditText field;
    }
    static final class Submission {
        final String ref,raw;final long revision;
        private Submission(String ref,Draft draft){this.ref=ref;raw=draft.text;revision=draft.revision;}
        String text(){return raw.trim();}
    }
    private final Map<String,Draft> drafts=new LinkedHashMap<>();
    private final Map<String,Submission> sending=new HashMap<>();
    private final Set<String> pending=new HashSet<>();
    private boolean closed;
    private Draft draft(String ref){return drafts.computeIfAbsent(ref,key->new Draft());}
    private static int position(int value,int length){return Math.max(0,Math.min(value,length));}
    private static void capture(Draft draft) {
        if(draft.field==null)return;
        draft.text=draft.field.getText().toString();
        draft.start=position(draft.field.getSelectionStart(),draft.text.length());
        draft.end=position(draft.field.getSelectionEnd(),draft.text.length());
    }
    void bind(String ref,EditText field,Runnable refresh) {
        Draft draft=draft(ref);capture(draft);pending.remove(ref);draft.field=field;
        field.setText(draft.text);field.setSelection(position(draft.start,draft.text.length()),position(draft.end,draft.text.length()));
        field.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){}
            public void afterTextChanged(Editable text){if(!closed&&draft.field==field){capture(draft);draft.revision++;}}
        });
        field.setOnFocusChangeListener((view,focused)->{
            if(closed||draft.field!=field)return;
            capture(draft);
            if(!focused&&pending.contains(ref))field.postDelayed(()->{
                if(!closed&&draft.field==field&&!field.hasFocus()&&pending.remove(ref))refresh.run();
            },250);
        });
    }
    boolean defer(String ref) {
        Draft draft=drafts.get(ref);
        if(!closed&&draft!=null&&draft.field!=null&&draft.field.hasFocus()){capture(draft);pending.add(ref);return true;}
        pending.remove(ref);return false;
    }
    Submission begin(String ref) {
        if(closed||sending.containsKey(ref))return null;
        Draft draft=draft(ref);capture(draft);if(draft.text.trim().isEmpty())return null;
        Submission ticket=new Submission(ref,draft);sending.put(ref,ticket);return ticket;
    }
    void saved(Submission ticket) {
        if(closed||sending.get(ticket.ref)!=ticket)return;
        sending.remove(ticket.ref);Draft draft=draft(ticket.ref);capture(draft);
        // A late save cannot erase a newer draft, even when the user has retyped identical text.
        if(draft.revision!=ticket.revision||!draft.text.equals(ticket.raw))return;
        draft.text="";draft.start=draft.end=0;draft.revision++;
        if(draft.field!=null){draft.field.setText("");draft.field.clearFocus();}
    }
    void failed(Submission ticket){if(sending.get(ticket.ref)==ticket)sending.remove(ticket.ref);}
    void save(Bundle state) {
        Bundle all=new Bundle();
        for(Map.Entry<String,Draft> entry:drafts.entrySet()) {
            Draft draft=entry.getValue();capture(draft);if(draft.text.isEmpty())continue;
            Bundle value=new Bundle();value.putString("text",draft.text);value.putInt("start",draft.start);value.putInt("end",draft.end);all.putBundle(entry.getKey(),value);
        }
        state.putBundle(STATE,all);
    }
    void restore(Bundle state) {
        if(state==null)return;Bundle all=state.getBundle(STATE);if(all==null)return;
        for(String ref:all.keySet()) {
            Bundle value=all.getBundle(ref);if(value==null)continue;
            Draft draft=draft(ref);draft.text=value.getString("text","");
            draft.start=position(value.getInt("start"),draft.text.length());draft.end=position(value.getInt("end"),draft.text.length());
        }
    }
    void close(){closed=true;pending.clear();sending.clear();for(Draft draft:drafts.values())draft.field=null;}
}
