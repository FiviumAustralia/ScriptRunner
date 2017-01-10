package com.fivium.scriptrunner2.builder;

import com.fivium.scriptrunner2.ex.ExManifestBuilder;
import com.fivium.scriptrunner2.ex.ExParser;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;

import java.util.TreeMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import org.junit.Before;
import org.junit.Test;

public class ManifestBuilderWithFileArgTest extends ManifestBuilderTest {

  public ManifestBuilderWithFileArgTest() {
    super();
  }

  @Override
  @Before
  public void setUp() throws ExManifestBuilder, ExParser {
    mManifestBuilder = new ManifestBuilder(new File(this.getClass().getResource("testfiles").getPath()), LABEL_TEST);
    PrintWriter lWriter = new PrintWriter(new StringWriter());
    mManifestBuilder.buildManifest(
        new File(this.getClass().getResource("testfiles/ScriptRunner/additionalprops.txt").getPath()), lWriter, "builder.cfg");
    mEntryMap = mManifestBuilder.getManifestEntryMap();
  }
}
