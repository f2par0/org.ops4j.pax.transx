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

import com.arjuna.ats.arjuna.common.Uid;
import com.arjuna.ats.arjuna.exceptions.ObjectStoreException;
import com.arjuna.ats.arjuna.objectstore.ObjectStoreIterator;
import com.arjuna.ats.arjuna.objectstore.StoreManager;
import com.arjuna.ats.arjuna.state.InputObjectState;
import com.arjuna.ats.arjuna.tools.log.TransactionTypeManager;
import com.arjuna.ats.arjuna.tools.osb.mbean.ObjStoreBrowser;
import com.arjuna.ats.arjuna.tools.osb.util.JMXServer;
import com.arjuna.ats.internal.arjuna.tools.log.EditableTransaction;
import org.jboss.narayana.osgi.jta.ObjStoreBrowserService;

import javax.management.AttributeList;
import javax.management.InstanceNotFoundException;
import javax.management.IntrospectionException;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanException;
import javax.management.MBeanInfo;
import javax.management.MBeanServer;
import javax.management.ObjectInstance;
import javax.management.ObjectName;
import javax.management.ReflectionException;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;


public class ObjStoreBrowserImpl implements ObjStoreBrowserService{
    private ObjStoreBrowser osb;
    private PrintStream printStream;
    private List<String> recordTypes = new ArrayList<String>();
    private String currentType = null;
    private String currentLog = "";
    private boolean attached = false;

    public ObjStoreBrowserImpl(ObjStoreBrowser osb) {
        try {
            printStream = new PrintStream(System.out, true, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            System.err.println("Encoding " + StandardCharsets.UTF_8.name() + " is not supported");
            throw new IllegalStateException(StandardCharsets.UTF_8.name() + " is not supported");
        }
        this.osb = osb;
    }

    @Override
    public void probe() throws MBeanException {
        osb.probe();
    }

    @Override
    public List<String> types() {
        recordTypes.clear();

        InputObjectState types = new InputObjectState();

        try {
            if (StoreManager.getRecoveryStore().allTypes(types)) {
                String typeName;

                do {
                    try {
                        typeName = types.unpackString();
                        if (!recordTypes.contains(typeName))
                            recordTypes.add(typeName);
                    } catch (IOException e) {
                        typeName = "";
                    }
                } while (typeName.length() != 0);
            }
        } catch (ObjectStoreException e) {
            System.out.println(e);
        }

        return recordTypes;
    }

    @Override
    public boolean select(String itype) {
        if (attached) {
            attached = false;
        }

        if (recordTypes.isEmpty()) {
            types();
        }

        if (!recordTypes.contains(itype)) {
            printStream.printf("%s is not a valid transaction type%n", itype);
            return false;
        } else {
            currentType = itype;
            return true;
        }
    }

    @Override
    public void list(String itype) {
        MBeanServer mbs = JMXServer.getAgent().getServer();
        Set<ObjectInstance> transactions;
        String osMBeanName = osb.getObjStoreBrowserMBeanName();

        if (itype != null) {
            if (select(itype) == false) return;
            osMBeanName += ",itype=" + itype;
        } else if (currentType != null) {
            osMBeanName += ",itype=" + currentType;
        } else {
            printStream.printf("No type selected%n");
            return;
        }

        try {
            transactions = mbs.queryMBeans(new ObjectName(osMBeanName + ",*"), null);
        } catch (Exception e) {
            System.out.println(e);
            return;
        }

        for (ObjectInstance oi : transactions) {
            String transactionId = oi.getObjectName().getCanonicalName();

            if (!transactionId.contains("puid") && transactionId.contains("itype")) {
                printStream.printf("Transaction: %s%n", oi.getObjectName());
                printStream.printf("-----------------------------------%n");

                String participantQuery =  transactionId + ",puid=*";
                try {
                    Set<ObjectInstance> participants = mbs.queryMBeans(new ObjectName(participantQuery), null);
                    printAtrributes(printStream, "\t", mbs, oi);
                    printStream.printf("\tParticipants:%n");
                    for (ObjectInstance poi : participants) {
                        printStream.printf("\t\tParticipant: %s%n", poi);
                        printAtrributes(printStream, "\t\t\t", mbs, poi);
                    }
                } catch (Exception e){

                }
                printStream.printf("%n");
            }
        }
    }

    private void printAtrributes(PrintStream printStream, String printPrefix, MBeanServer mbs, ObjectInstance oi)
            throws IntrospectionException, InstanceNotFoundException, ReflectionException {
        MBeanInfo info = mbs.getMBeanInfo( oi.getObjectName() );
        MBeanAttributeInfo[] attributeArray = info.getAttributes();
        int i = 0;
        String[] attributeNames = new String[attributeArray.length];

        for (MBeanAttributeInfo ai : attributeArray)
            attributeNames[i++] = ai.getName();

        AttributeList attributes = mbs.getAttributes(oi.getObjectName(), attributeNames);

        for (javax.management.Attribute attribute : attributes.asList()) {
            Object value = attribute.getValue();
            String v =  value == null ? "null" : value.toString();

            printStream.printf("%s%s=%s%n", printPrefix, attribute.getName(), v);
        }
    }

    @Override
    public void attach(String id) {
        if (attached)
            System.err.println("Already attached.");
        else {
            try {
                if (supportedLog(id)) {
                    currentLog = id;
                    attached = true;
                } else {
                    System.err.println("can not attach to id " + id + " with type /" + currentType);
                }
            } catch (Exception e) {
                System.err.println(e);
                e.printStackTrace();
            }
        }
    }

    @Override
    public void detach() {
        if (!attached)
            System.err.println("Not attached.");

        currentLog = "";
        attached = false;
    }

    @Override
    public void forget(int idx) {
        if (!attached) {
            System.err.println("Not attached.");
        } else if (!currentType.contains("AtomicAction")) {
            System.err.println("Can not support this type");
        } else {
            Uid uid = new Uid(currentLog);
            EditableTransaction act = TransactionTypeManager.getInstance().getTransaction("AtomicAction", uid);
            try {
                act.moveHeuristicToPrepared(idx);
            } catch (IndexOutOfBoundsException ex) {
                System.err.println("Invalid index.");
            }
        }
    }

    @Override
    public void delete(int idx) {
        if (!attached) {
            System.err.println("Not attached.");
        } else if (!currentType.contains("AtomicAction")) {
            System.err.println("Can not support this type");
        } else {
            Uid uid = new Uid(currentLog);
            EditableTransaction act = TransactionTypeManager.getInstance().getTransaction("AtomicAction", uid);
            try {
                act.deleteHeuristicParticipant(idx);
            } catch (IndexOutOfBoundsException ex) {
                System.err.println("Invalid index.");
            }
        }

    }

    private final boolean supportedLog (String logID) throws ObjectStoreException, IOException {
        Uid id = new Uid(logID);

        if (id.equals(Uid.nullUid())) {
            System.err.println(logID + " is null Uid");
        } else if (currentType == null) {
            printStream.printf("No type selected%n");
        } else if (!currentType.contains("AtomicAction")) {
            printStream.printf("Can not support this type");
        } else {
            ObjectStoreIterator iter = new ObjectStoreIterator(StoreManager.getRecoveryStore(), "/" + currentType);
            Uid u;

            do {
                u = iter.iterate();

                if (u.equals(id))
                    return true;
            } while (Uid.nullUid().notEquals(u));
        }

        return false;
    }

    @Override
    public void start() {
        osb.start();
    }

    @Override
    public void stop() {
        osb.stop();
    }
}