/*
 * Copyright 2021 OPS4J.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.narayana.osgi.jta.internal;

import jakarta.transaction.InvalidTransactionException;
import jakarta.transaction.SystemException;
import jakarta.transaction.Transaction;
import jakarta.transaction.UserTransaction;

import com.arjuna.ats.internal.jta.transaction.arjunacore.TransactionManagerImple;


public class OsgiTransactionManager extends TransactionManagerImple implements UserTransaction {

    public interface Listener {
        void resumed(Transaction transaction);
        void suspended(Transaction transaction);
    }

    private Listener listener;

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    @Override
    public Transaction suspend() throws SystemException {
        Transaction tx = super.suspend();
        if (listener != null) {
            listener.suspended(tx);
        }
        return tx;
    }

    @Override
    public void resume(Transaction which) throws InvalidTransactionException, IllegalStateException, SystemException {
        super.resume(which);
        if (listener != null) {
            listener.resumed(which);
        }
    }
}