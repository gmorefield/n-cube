package com.cedarsoftware.ncube

import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.junit.After
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test

import java.lang.reflect.Field

import static com.cedarsoftware.ncube.NCubeConstants.NCUBE_PARAMS_GENERATED_CLASSES_DIR
import static com.cedarsoftware.ncube.NCubeConstants.NCUBE_PARAMS_GENERATED_SOURCES_DIR
import static org.junit.Assert.*

/**
 * @author Greg Morefield (morefigs@hotmail.com)
 *         <br/>
 *         Copyright (c) Cedar Software LLC
 *         <br/><br/>
 *         Licensed under the Apache License, Version 2.0 (the 'License')
 *         you may not use this file except in compliance with the License.
 *         You may obtain a copy of the License at
 *         <br/><br/>
 *         http://www.apache.org/licenses/LICENSE-2.0
 *         <br/><br/>
 *         Unless required by applicable law or agreed to in writing, software
 *         distributed under the License is distributed on an 'AS IS' BASIS,
 *         WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *         See the License for the specific language governing permissions and
 *         limitations under the License.
 */
class TestL3Cache
{
    private NCube cp
    private NCube proto

    private NCube testCube
    private File sourcesDir
    private File classesDir

    private static File targetDir
    private static String savedNcubeParams

    private static final Logger LOG = LogManager.getLogger(TestL3Cache.class)

    @BeforeClass
    static void init()
    {
        targetDir = new File ('target')
        assertTrue(targetDir.exists() && targetDir.isDirectory())
        savedNcubeParams = System.getProperty('NCUBE_PARAMS')
    }

    @Before
    void setUp()
    {
        TestingDatabaseHelper.setupDatabase()
        reloadCubes()

        sourcesDir = new File("${targetDir.path}/TestL3Cache-sources")
        if (sourcesDir.exists()) {
            assertTrue('directory should be purged',sourcesDir.deleteDir())
        }
        assertFalse('directory should not already exist',sourcesDir.exists())

        classesDir = new File("${targetDir.path}/TestL3Cache-classes")
        if (classesDir.exists()) {
            assertTrue('directory should be purged',classesDir.deleteDir())
        }
        assertFalse('directory should not already exist',classesDir.exists())

        configureSysParams(sourcesDir.path,classesDir.path)
    }

    @After
    void tearDown()
    {
        TestingDatabaseHelper.tearDownDatabase()
        if (savedNcubeParams) {
            System.setProperty('NCUBE_PARAMS',savedNcubeParams)
        }
        else {
            System.clearProperty('NCUBE_PARAMS')
        }
        NCubeManager.clearSysParams()
    }

    /**
     * Test verifies that the sources and classes directory will be created
     * with the appropriate *.groovy and *.class files
     */
    @Test
    void testCreateCache()
    {
        assertFalse(sourcesDir.exists())
        assertFalse(classesDir.exists())
        assertTrue(getLoadedClasses().isEmpty())

        Map output = [:]
        testCube.getCell([name:'simple'],output)

        String className = output.simple
        assertTrue(className.startsWith('N_'))
        assertTrue(sourcesDir.exists())
        assertTrue(classesDir.exists())
        verifySourceAndClassFilesExist(className)
        assertEquals(className,findLoadedClass(className).simpleName)
    }

    /**
     * Test verifies that classes generated/loaded for cell expressions are cleared with the cache
     * and then are re-loaded from the L3 cache location
     */
    @Test
    void testClearCache()
    {
        // load the expression initially
        Map output = [:]
        testCube.getCell([name:'simple'],output)
        String className = output.simple

        verifySourceAndClassFilesExist(className)
        assertEquals(className,findLoadedClass(className).simpleName)

        // clear the cache, but make sure l3 cache still exists
        reloadCubes()
        assertTrue(getLoadedClasses().isEmpty())
        verifySourceAndClassFilesExist(className)

        // clear the source file in order to detect if a recompile occurs or class loaded from l3 cache
        File sourceFile = new File ("${sourcesDir}/ncube/grv/exp/${className}.groovy")
        assertTrue(sourceFile.exists())
        sourceFile.delete()
        assertFalse(sourceFile.exists())

        // re-execute the cube and ensure class was reloaded
        output.clear()
        testCube.getCell([name:'simple'],output)
        assertEquals(className,findLoadedClass(className).simpleName)
        assertFalse(sourceFile.exists())
    }

    /**
     * Tests that NCube compile() generates sources and classes for all expressions
     * that are defined as cells or meta properties
     */
    @Test
    void testCompile()
    {
        testCube.compile()

        // exercise ncube in a variety of ways to invoke cells and meta properties
        Map output = [:]
        testCube.getCell([name:'simple'],output)
        testCube.getCell([useRule:true],output)
        testCube.extractMetaPropertyValue(testCube.getMetaProperty('metaTest'),[:],output)
        Axis nameAxis = testCube.getAxis('name')
        testCube.extractMetaPropertyValue(nameAxis.getMetaProperty('metaTest'),[:],output)
        testCube.extractMetaPropertyValue(nameAxis.findColumn('simple').getMetaProperty('metaTest'),[:],output)

        // validate sources/classes have been created for the expressions
        verifySourceAndClassFilesExist(output.metaCube as String)
        verifySourceAndClassFilesExist(output.metaAxis as String)
        verifySourceAndClassFilesExist(output.metaColumn as String)
        verifySourceAndClassFilesExist(output.metaRule as String)
        verifySourceAndClassFilesExist(output.simple as String)
    }

    /**
     * Test verifies that inner classes are supported
     */
    @Test
    void testExpressionWithInnerClass()
    {
        Map output = [:]
        testCube.getCell([name:'innerClass'],output)

        String className = output.innerClass
        String innerClassName = "${className}\$1"
        verifySourceAndClassFilesExist(className)
        verifySourceAndClassFilesExist(innerClassName)
        assertEquals(className,findLoadedClass(className).simpleName)

        reloadCubes()
        assertTrue(getLoadedClasses().isEmpty())
        verifySourceAndClassFilesExist(className)
        verifySourceAndClassFilesExist(innerClassName)

        output.clear()
        testCube.getCell([name:'innerClass'],output)
        assertEquals(className,findLoadedClass(className).simpleName)
    }

    /**
     * Test verifies that sources and classes can be pointed to the same directory
     */
    @Test
    void testUsingSameDirectory()
    {
        sourcesDir = new File ("${targetDir}/single-l3cache")
        classesDir = sourcesDir
        configureSysParams(sourcesDir.path,classesDir.path)

        assertFalse(sourcesDir.exists())
        assertEquals(sourcesDir,classesDir)
        assertTrue(getLoadedClasses().isEmpty())

        Map output = [:]
        testCube.getCell([name:'simple'],output)

        // validate class loaded, but no cache directories created
        String className = output.simple
        verifySourceAndClassFilesExist(className)
        assertEquals(className,findLoadedClass(className).simpleName)
    }

    /**
     * Test verifies that invalid sources and classes directories are ignored
     */
    @Test
    void testInvalidParameters()
    {
        File sourceFile = new File ("${targetDir}/sources.txt")
        sourceFile.write('source parameter that is not a directory')
        File classesFile = new File ("${targetDir}/classes.txt")
        classesFile.write('class parameter that is not a directory')

        configureSysParams(sourceFile.path,classesFile.path)

        assertTrue(sourceFile.exists())
        assertTrue(classesFile.exists())
        assertTrue(getLoadedClasses().isEmpty())

        Map output = [:]
        testCube.getCell([name:'simple'],output)

        // validate class loaded, but no cache directories created
        String className = output.simple
        assertEquals(className,findLoadedClass(className).simpleName)
    }

    @Test
    void testSysPrototypeChange()
    {
        Map output = [:]
        testCube.getCell([name:'simple'],output)

        String origClassName = output.simple
        assertTrue(sourcesDir.exists())
        assertTrue(classesDir.exists())
        verifySourceAndClassFilesExist(origClassName)
        assertEquals(origClassName,findLoadedClass(origClassName).simpleName)

        reloadCubes()
        loadTestCube(sysPrototypeDef.bytes)

        output.clear()
        testCube.getCell([name:'simple'],output)

        String newClassName = output.simple
        assertNotEquals(origClassName,newClassName)
    }

    private void configureSysParams(srcDirPath,clsDirPath)
    {
        NCubeManager.clearSysParams()

        System.setProperty("NCUBE_PARAMS", "{\"${NCUBE_PARAMS_GENERATED_SOURCES_DIR}\":\"${srcDirPath}\",\"${NCUBE_PARAMS_GENERATED_CLASSES_DIR}\":\"${clsDirPath}\"}")
        assertEquals(srcDirPath,NCubeManager.getSystemParams()[NCUBE_PARAMS_GENERATED_SOURCES_DIR])
        assertEquals(clsDirPath,NCubeManager.getSystemParams()[NCUBE_PARAMS_GENERATED_CLASSES_DIR])
    }

    private void reloadCubes() {
        NCubeManager.clearCache()

        NCubeManager.getNCubeFromResource('sys.classpath.threading.json')
        cp = NCubeManager.getCube(ApplicationID.testAppId, 'sys.classpath')
        NCubeManager.addCube(ApplicationID.testAppId, cp)

        NCubeManager.getNCubeFromResource('sys.prototype.json')
        proto = NCubeManager.getCube(ApplicationID.testAppId, 'sys.prototype')
        NCubeManager.addCube(ApplicationID.testAppId, proto)

        testCube = loadTestCube(L3CacheCubeDef.bytes)
    }

    private NCube loadTestCube(byte [] bytes)
    {
        NCube testCube = NCube.createCubeFromStream(new ByteArrayInputStream(bytes))
        assertNotNull(testCube)
        NCubeManager.addCube(ApplicationID.testAppId,testCube)

        return testCube
    }

    private boolean verifySourceAndClassFilesExist(String fileName) {
        if (!fileName.contains('$')) {
            File sourceFile = new File ("${sourcesDir}/ncube/grv/exp/${fileName}.groovy")
            assertTrue(fileName,sourceFile.exists())
        }

        File classFile = new File ("${classesDir}/ncube/grv/exp/${fileName}.class")
        assertTrue(fileName,classFile.exists())
    }

    private Class findLoadedClass(String name)
    {
        return getLoadedClasses().find { it.simpleName == name}
    }

    private List<Class> getLoadedClasses()
    {
        GroovyClassLoader gcl = cp.getCell([:]) as GroovyClassLoader
        Field classesField = ClassLoader.class.getDeclaredField('classes')
        classesField.setAccessible(true)

        if (NCubeManager.getSystemParams()[NCUBE_PARAMS_GENERATED_SOURCES_DIR])
            return classesField.get(gcl) + classesField.get(gcl.parent)
        else
            return classesField.get(gcl)
    }


    static String L3CacheCubeDef='''{
  "ncube":"test.L3CacheTest",
  "metaTest":{
    "type":"exp",
    "value":"output.metaCube = this.class.simpleName"
  },
  "axes":[
    {
      "id":1,
      "name":"name",
      "hasDefault":true,
      "metaTest":{
        "type":"exp",
        "value":"output.metaAxis = this.class.simpleName"
      },
      "type":"DISCRETE",
      "valueType":"STRING",
      "preferredOrder":0,
      "fireAll":true,
      "columns":[
        {
          "id":1000329189157,
          "type":"string",
          "metaTest":{
            "type":"exp",
            "value":"output.metaColumn = this.class.simpleName"
          },
          "value":"simple"
        },
        {
          "id":1001649720956,
          "type":"string",
          "value":"simple-clone"
        },
        {
          "id":"innerClass",
          "type":"string",
          "value":"innerClass"
        }
      ]
    },
    {
      "id":2,
      "name":"type",
      "hasDefault":true,
      "type":"RULE",
      "valueType":"EXPRESSION",
      "preferredOrder":1,
      "fireAll":true,
      "columns":[
        {
          "id":2000714454923,
          "type":"exp",
          "name":"useRule",
          "value":"if (input.useRule) {output.metaRule = this.class.simpleName}\nreturn input.useRule"
        }
      ]
    }
  ],
  "cells":[
    {
      "id":[
        1000329189157
      ],
      "type":"exp",
      "value":"output.simple = this.class.simpleName\nreturn output.simple"
    },
    {
      "id":[
        1001649720956
      ],
      "type":"exp",
      "value":"output.simple = this.class.simpleName\nreturn output.simple"
    },
    {
      "id":[
        "innerClass"
      ],
      "type":"exp",
      "value":"Comparator c = new Comparator() { int compare(Object o1, Object o2) { return 0 } }\noutput.innerClass = this.class.simpleName\nreturn output.innerClass"
    }
  ]
}'''

    static String sysPrototypeDef = '''{
  "ncube": "sys.prototype",
  "axes": [
    {
      "name": "sys.property",
      "hasDefault": false,
      "type": "DISCRETE",
      "valueType": "STRING",
      "preferredOrder": 1,
      "fireAll": true,
      "columns": [
        {
          "id": "imports",
          "type": "string",
          "value": "exp.imports"
        },
        {
          "id": "class",
          "type": "string",
          "value": "exp.class"
        }
      ]
    }
  ],
  "cells": [
    {
      "id":["imports"],
      "type":"string",
      "value":"javax.net.ssl.HostnameVerifier"
    }
  ]
}'''

}
