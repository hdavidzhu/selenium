// Licensed to the Software Freedom Conservancy (SFC) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The SFC licenses this file
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

package org.openqa.selenium.server.htmlrunner;


import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.thoughtworks.selenium.Selenium;
import com.thoughtworks.selenium.SeleniumException;

import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Logger;

public class CoreTestCase {

  private static final Logger LOG = Logger.getLogger(CoreTestCase.class.getName());

  private static final ImmutableMap<String, CoreStepFactory> STEP_FACTORY =
    ImmutableMap.<String, CoreStepFactory>builder()
    .putAll(new ReflectivelyDiscoveredSteps().get())
    .putAll(new NonReflectiveSteps().get())
    .build();
  private String url;

  public CoreTestCase(String url) {
    this.url = Preconditions.checkNotNull(url);
  }

  public void run(Results results, WebDriver driver, Selenium selenium) {
    String currentUrl = driver.getCurrentUrl();
    if (!url.equals(currentUrl)) {
      driver.get(url);
    }

    String rawSource = driver.getPageSource();
    List<LoggableStep> steps = findCommands(driver);
    TestState state = new TestState();
    List<StepResult> stepResults = new ArrayList<>(steps.size());
    NextStepDecorator decorator = NextStepDecorator.IDENTITY;
    for (LoggableStep step : steps) {
      LOG.info(step.toString());
      decorator = Preconditions.checkNotNull(decorator.evaluate(step, selenium, state), step);
      stepResults.add(new StepResult(step, decorator.getCause()));
      if (!decorator.isOkayToContinueTest()) {
        break;
      } else {
        stepResults.add(new StepResult(step, null));
      }
    }

    results.addTest(rawSource, stepResults);
  }

  private List<LoggableStep> findCommands(WebDriver driver) {
    // Let's just run and hide in the horror that is JS for the sake of speed.
    List<List<String>> rawSteps = (List<List<String>>) ((JavascriptExecutor) driver).executeScript(
      "var toReturn = [];\n" +
      "var tables = document.getElementsByTagName('table');\n" +
      "for (var i = 0; i < tables.length; i++) {" +
      "  for (var rowCount = 0; rowCount < tables[i].rows.length; rowCount++) {\n" +
      "    if (tables[i].rows[rowCount].cells.length < 3) {\n" +
      "      continue;\n" +
      "    }\n" +
      "    var cells = tables[i].rows[rowCount].cells;\n" +
      "    toReturn.push([cells[0].textContent, cells[1].textContent, cells[2].textContent]);\n" +
      "  }\n" +
      "}\n" +
      "return toReturn;");

    ImmutableList.Builder<LoggableStep> steps = ImmutableList.builder();
    Iterator<List<String>> stepIterator = rawSteps.iterator();
    while (stepIterator.hasNext()) {
      List<String> step =  stepIterator.next();
      if (!STEP_FACTORY.containsKey(step.get(0))) {
        throw new SeleniumException("Unknown command: " + step.get(0));
      }
      steps.add(new LoggableStep(
        STEP_FACTORY.get(step.get(0)).create(step.get(1), step.get(2)),
        step.get(0),
        step.get(1),
        step.get(2)));
    }
    return steps.build();
  }

  private static class LoggableStep implements CoreStep {
    private final CoreStep actualStep;
    private final String command;
    private final String locator;
    private final String value;

    public LoggableStep(CoreStep toWrap, String command, String locator, String value) {
      this.actualStep = toWrap;
      this.command = command;
      this.locator = locator;
      this.value = value;
    }

    @Override
    public NextStepDecorator execute(Selenium selenium, TestState state) {
      return actualStep.execute(selenium, state);
    }

    @Override
    public String toString() {
      return String.format("|%s | %s | %s |", command, locator, value);
    }
  }

  static class StepResult {
    private final LoggableStep step;
    private final Throwable cause;

    public StepResult(LoggableStep step, Throwable cause) {
      this.step = Preconditions.checkNotNull(step);
      this.cause = cause;
    }

    public boolean isSuccessful() {
      return cause == null;
    }

    public boolean isError() {
      return cause instanceof SeleniumException;
    }

    public boolean isFailure() {
      return !isSuccessful() && !isError();
    }

    public String getStepLog() {
      return step.toString();
    }
  }
}
