// Modifications copyright (C) 2017, Baidu.com, Inc.
// Copyright 2017 The Apache Software Foundation

// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package com.baidu.palo.catalog;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.easymock.EasyMock;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.powermock.api.easymock.PowerMock;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import com.baidu.palo.catalog.MaterializedIndex.IndexState;
import com.baidu.palo.common.FeConstants;
import com.baidu.palo.persist.CreateTableInfo;
import com.baidu.palo.persist.EditLog;

@RunWith(PowerMockRunner.class)
@PowerMockIgnore("org.apache.log4j.*")
@PrepareForTest(Catalog.class)
public class DatabaseTest {

    private Database db;
    private long dbId = 10000;

    private Catalog catalog;
    private EditLog editLog;

    @Before
    public void Setup() {
        db = new Database(dbId, "dbTest");

        editLog = EasyMock.createMock(EditLog.class);
        editLog.logCreateTable(EasyMock.anyObject(CreateTableInfo.class));
        EasyMock.expectLastCall().anyTimes();
        EasyMock.replay(editLog);

        catalog = EasyMock.createMock(Catalog.class);
        EasyMock.expect(catalog.getEditLog()).andReturn(editLog);
        EasyMock.replay(catalog);

        PowerMock.mockStatic(Catalog.class);
        EasyMock.expect(Catalog.getInstance()).andReturn(catalog).anyTimes();
        EasyMock.expect(Catalog.getCurrentCatalogJournalVersion()).andReturn(FeConstants.meta_version).anyTimes();
        PowerMock.replay(Catalog.class);
    }

    @Test
    public void lockTest() {
        db.readLock();
        try {
            Assert.assertFalse(db.tryWriteLock(0, TimeUnit.SECONDS));
            Assert.assertTrue(db.tryReadLock(0, TimeUnit.SECONDS));
            db.readUnlock();
        } finally {
            db.readUnlock();
        }

        db.writeLock();
        try {
            Assert.assertTrue(db.tryWriteLock(0, TimeUnit.SECONDS));
            Assert.assertTrue(db.tryReadLock(0, TimeUnit.SECONDS));
        } finally {
            db.writeUnlock();
        }
    }

    @Test
    public void createAndDropPartitionTest() {
        Assert.assertEquals("dbTest", db.getName());
        Assert.assertEquals(dbId, db.getId());

        MaterializedIndex baseIndex = new MaterializedIndex(10001, IndexState.NORMAL);
        Partition partition = new Partition(20000L, "baseTable", baseIndex, new RandomDistributionInfo(10));
        List<Column> baseSchema = new LinkedList<Column>();
        OlapTable table = new OlapTable(2000, "baseTable", baseSchema, KeysType.AGG_KEYS, 
                                        new SinglePartitionInfo(), new RandomDistributionInfo(10));
        table.addPartition(partition);

        // create
        Assert.assertTrue(db.createTable(table));
        // duplicate
        Assert.assertFalse(db.createTable(table));

        Assert.assertEquals(table, db.getTable(table.getId()));
        Assert.assertEquals(table, db.getTable(table.getName()));

        Assert.assertEquals(1, db.getTables().size());
        Assert.assertEquals(table, db.getTables().get(0));

        Assert.assertEquals(1, db.getTableNamesWithLock().size());
        for (String tableFamilyGroupName : db.getTableNamesWithLock()) {
            Assert.assertEquals(table.getName(), tableFamilyGroupName);
        }

        // drop
        // drop not exist tableFamily
        db.dropTable("invalid");
        Assert.assertEquals(1, db.getTables().size());
        db.dropTableWithLock("invalid");
        Assert.assertEquals(1, db.getTables().size());

        // drop normal
        db.dropTableWithLock(table.getName());
        Assert.assertEquals(0, db.getTables().size());

        db.createTable(table);
        db.dropTable(table.getName());
        Assert.assertEquals(0, db.getTables().size());
    }

    @Test
    public void testSerialization() throws Exception {
        // 1. Write objects to file
        File file = new File("./database");
        file.createNewFile();
        DataOutputStream dos = new DataOutputStream(new FileOutputStream(file));
        
        // db1
        Database db1 = new Database();
        db1.write(dos);
        
        // db2
        Database db2 = new Database(2, "db2");
        List<Column> columns = new ArrayList<Column>();
        columns.add(new Column("column2", 
                        ColumnType.createType(PrimitiveType.TINYINT), false, AggregateType.MIN, "", ""));
        columns.add(new Column("column3", 
                        ColumnType.createType(PrimitiveType.SMALLINT), false, AggregateType.SUM, "", ""));
        columns.add(new Column("column4", 
                        ColumnType.createType(PrimitiveType.INT), false, AggregateType.REPLACE, "", ""));
        columns.add(new Column("column5", 
                        ColumnType.createType(PrimitiveType.BIGINT), false, AggregateType.REPLACE, "", ""));
        columns.add(new Column("column6", 
                        ColumnType.createType(PrimitiveType.FLOAT), false, AggregateType.REPLACE, "", ""));
        columns.add(new Column("column7", 
                        ColumnType.createType(PrimitiveType.DOUBLE), false, AggregateType.REPLACE, "", ""));
        columns.add(new Column("column8", ColumnType.createChar(10), true, null, "", ""));
        columns.add(new Column("column9", ColumnType.createVarchar(10), true, null, "", ""));
        columns.add(new Column("column10", ColumnType.createType(PrimitiveType.DATE), true, null, "", ""));
        columns.add(new Column("column11", ColumnType.createType(PrimitiveType.DATETIME), true, null, "", ""));

        MaterializedIndex index = new MaterializedIndex(1, IndexState.NORMAL);
        Partition partition = new Partition(20000L, "table", index, new RandomDistributionInfo(10));
        OlapTable table = new OlapTable(1000, "table", columns, KeysType.AGG_KEYS,
                                        new SinglePartitionInfo(), new RandomDistributionInfo(10));
        table.addPartition(partition);
        db2.createTable(table);
        db2.write(dos);
        

        dos.flush();
        dos.close();
        
        // 2. Read objects from file
        DataInputStream dis = new DataInputStream(new FileInputStream(file));
        
        Database rDb1 = new Database();
        rDb1.readFields(dis);
        Assert.assertTrue(rDb1.equals(db1));
        
        Database rDb2 = new Database();
        rDb2.readFields(dis);
        Assert.assertTrue(rDb2.equals(db2));
        
        // 3. delete files
        dis.close();
        file.delete();
    }
}
