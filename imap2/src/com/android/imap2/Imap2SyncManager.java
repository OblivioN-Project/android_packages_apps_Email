/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.imap2;

import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteCallbackList;
import android.os.RemoteException;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;

import com.android.emailcommon.Api;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.EmailContent.Attachment;
import com.android.emailcommon.provider.EmailContent.MailboxColumns;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.HostAuth;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.provider.ProviderUnavailableException;
import com.android.emailcommon.service.AccountServiceProxy;
import com.android.emailcommon.service.EmailServiceCallback;
import com.android.emailcommon.service.IEmailService;
import com.android.emailcommon.service.IEmailServiceCallback;
import com.android.emailcommon.service.IEmailServiceCallback.Stub;
import com.android.emailcommon.service.SearchParams;
import com.android.emailsync.AbstractSyncService;
import com.android.emailsync.PartRequest;
import com.android.emailsync.SyncManager;
import com.android.mail.providers.UIProvider.AccountCapabilities;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

public class Imap2SyncManager extends SyncManager {

    // Callbacks as set up via setCallback
    private static final RemoteCallbackList<IEmailServiceCallback> mCallbackList =
        new RemoteCallbackList<IEmailServiceCallback>();

    private static final EmailServiceCallback sCallbackProxy =
            new EmailServiceCallback(mCallbackList);

    /**
     * Create our EmailService implementation here.
     */
    private final IEmailService.Stub mBinder = new IEmailService.Stub() {

        @Override
        public int getApiLevel() {
            return Api.LEVEL;
        }

        @Override
        public Bundle validate(HostAuth hostAuth) throws RemoteException {
            return new Imap2SyncService(Imap2SyncManager.this,
                    new Mailbox()).validateAccount(hostAuth, Imap2SyncManager.this);
        }

        @Override
        public Bundle autoDiscover(String userName, String password) throws RemoteException {
            return null;
        }

        @Override
        public void startSync(long mailboxId, boolean userRequest) throws RemoteException {
            SyncManager imapService = INSTANCE;
            if (imapService == null) return;
            Imap2SyncService svc = (Imap2SyncService) imapService.mServiceMap.get(mailboxId);
            if (svc == null) {
                startManualSync(mailboxId, userRequest ? SYNC_UI_REQUEST : SYNC_SERVICE_START_SYNC,
                        null);
            } else {
                svc.ping();
            }
        }

        @Override
        public void stopSync(long mailboxId) throws RemoteException {
            stopManualSync(mailboxId);
        }

        @Override
        public void loadAttachment(long attachmentId, boolean background) throws RemoteException {
            Attachment att = Attachment.restoreAttachmentWithId(Imap2SyncManager.this, attachmentId);
            log("loadAttachment " + attachmentId + ": " + att.mFileName);
            sendMessageRequest(new PartRequest(att, null, null));
        }

        @Override
        public void updateFolderList(long accountId) throws RemoteException {
            //***
            //reloadFolderList(ImapService.this, accountId, false);
        }

        @Override
        public void hostChanged(long accountId) throws RemoteException {
            SyncManager exchangeService = INSTANCE;
            if (exchangeService == null) return;
            ConcurrentHashMap<Long, SyncError> syncErrorMap = exchangeService.mSyncErrorMap;
            // Go through the various error mailboxes
            for (long mailboxId: syncErrorMap.keySet()) {
                SyncError error = syncErrorMap.get(mailboxId);
                // If it's a login failure, look a little harder
                Mailbox m = Mailbox.restoreMailboxWithId(exchangeService, mailboxId);
                // If it's for the account whose host has changed, clear the error
                // If the mailbox is no longer around, remove the entry in the map
                if (m == null) {
                    syncErrorMap.remove(mailboxId);
                } else if (error != null && m.mAccountKey == accountId) {
                    error.fatal = false;
                    error.holdEndTime = 0;
                }
            }
            // Stop any running syncs
            exchangeService.stopAccountSyncs(accountId, true);
            // Kick ExchangeService
            kick("host changed");
        }

        @Override
        public void setLogging(int flags) throws RemoteException {
            // Protocol logging
            //Eas.setUserDebug(flags);
            // Sync logging
            setUserDebug(flags);
        }

        @Override
        public void sendMeetingResponse(long messageId, int response) throws RemoteException {
            // Not used in IMAP
        }

        @Override
        public void loadMore(long messageId) throws RemoteException {
        }

        // The following three methods are not implemented in this version
        @Override
        public boolean createFolder(long accountId, String name) throws RemoteException {
            return false;
        }

        @Override
        public boolean deleteFolder(long accountId, String name) throws RemoteException {
            return false;
        }

        @Override
        public boolean renameFolder(long accountId, String oldName, String newName)
                throws RemoteException {
            return false;
        }

        @Override
        public void setCallback(IEmailServiceCallback cb) throws RemoteException {
            mCallbackList.register(cb);
        }

        @Override
        public void deleteAccountPIMData(long accountId) throws RemoteException {
            // Not required for IMAP
        }

        @Override
        public int searchMessages(long accountId, SearchParams params, long destMailboxId)
                throws RemoteException {
            // TODO Auto-generated method stub
            return 0;
        }

        @Override
        public void sendMail(long accountId) throws RemoteException {
            // Not required for IMAP
        }

        @Override
        public int getCapabilities(long accountId) throws RemoteException {
            return AccountCapabilities.SYNCABLE_FOLDERS |
                    AccountCapabilities.FOLDER_SERVER_SEARCH |
                    AccountCapabilities.UNDO;
        }
    };

    static public IEmailServiceCallback callback() {
        return sCallbackProxy;
    }

    @Override
    public AccountObserver getAccountObserver(Handler handler) {
        return new AccountObserver(handler) {
            @Override
            public void newAccount(long acctId) {
                // Create the Inbox for the account if it doesn't exist
                Context context = getContext();
                Account acct = Account.restoreAccountWithId(context, acctId);
                if (acct == null) return;
                long inboxId = Mailbox.findMailboxOfType(context, acctId, Mailbox.TYPE_INBOX);
                if (inboxId != Mailbox.NO_MAILBOX) {
                    return;
                }
                Mailbox inbox = new Mailbox();
                inbox.mDisplayName = context.getString(R.string.mailbox_name_server_inbox);
                inbox.mServerId = "Inbox";
                inbox.mAccountKey = acct.mId;
                inbox.mType = Mailbox.TYPE_INBOX;
                inbox.mSyncInterval = acct.mSyncInterval;
                inbox.save(getContext());
                log("Creating inbox for account: " + acct.mDisplayName);
                Imap2SyncManager.kick("New account");
                // Need to sync folder list first; sigh
                Imap2SyncService svc = new Imap2SyncService(Imap2SyncManager.this, acct);
                try {
                    svc.loadFolderList();
                    mResolver.update(
                            ContentUris.withAppendedId(EmailContent.PICK_TRASH_FOLDER_URI, acctId),
                            new ContentValues(), null, null);
                } catch (IOException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
            }
        };
    }

    @Override
    public void onStartup() {
        // No special behavior
    }

    private static final String ACCOUNT_KEY_IN = MailboxColumns.ACCOUNT_KEY + " in (";
    private String mAccountSelector;
    @Override
    public String getAccountsSelector() {
        if (mAccountSelector == null) {
            StringBuilder sb = new StringBuilder(ACCOUNT_KEY_IN);
            boolean first = true;
            synchronized (mAccountList) {
                for (Account account : mAccountList) {
                    if (!first) {
                        sb.append(',');
                    } else {
                        first = false;
                    }
                    sb.append(account.mId);
                }
            }
            sb.append(')');
            mAccountSelector = sb.toString();
        }
        return mAccountSelector;
    }

    @Override
    public AbstractSyncService getServiceForMailbox(Context context, Mailbox mailbox) {
        return new Imap2SyncService(context, mailbox);
    }

    @Override
    public AccountList collectAccounts(Context context, AccountList accounts) {
        ContentResolver resolver = context.getContentResolver();
        Cursor c = resolver.query(Account.CONTENT_URI, Account.CONTENT_PROJECTION, null, null,
                null);
        // We must throw here; callers might use the information we provide for reconciliation, etc.
        if (c == null) throw new ProviderUnavailableException();
        try {
            while (c.moveToNext()) {
                long hostAuthId = c.getLong(Account.CONTENT_HOST_AUTH_KEY_RECV_COLUMN);
                if (hostAuthId > 0) {
                    HostAuth ha = HostAuth.restoreHostAuthWithId(context, hostAuthId);
                    if (ha != null && ha.mProtocol.equals("imap2")) {
                        Account account = new Account();
                        account.restore(c);
                        account.mHostAuthRecv = ha;
                        accounts.add(account);
                    }
                }
            }
        } finally {
            c.close();
        }
        return accounts;
    }

    @Override
    public String getAccountManagerType() {
        return "com.android.imap2";
    }

    @Override
    public String getServiceIntentAction() {
        return "com.android.email.IMAP2_INTENT";
    }

    @Override
    public Stub getCallbackProxy() {
        return sCallbackProxy;
    }

    @Override
    protected void runAccountReconcilerSync(Context context) {
        alwaysLog("Reconciling accounts...");
        new AccountServiceProxy(context).reconcileAccounts("imap2", getAccountManagerType());
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }
}