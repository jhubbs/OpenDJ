/*
 * CDDL HEADER START
 *
 * The contents of this file are subject to the terms of the
 * Common Development and Distribution License, Version 1.0 only
 * (the "License").  You may not use this file except in compliance
 * with the License.
 *
 * You can obtain a copy of the license at legal-notices/CDDLv1_0.txt
 * or http://forgerock.org/license/CDDLv1.0.html.
 * See the License for the specific language governing permissions
 * and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL HEADER in each
 * file and include the License file at legal-notices/CDDLv1_0.txt.
 * If applicable, add the following below this CDDL HEADER, with the
 * fields enclosed by brackets "[]" replaced with your own identifying
 * information:
 *      Portions Copyright [yyyy] [name of copyright owner]
 *
 * CDDL HEADER END
 *
 *
 *      Copyright 2006-2008 Sun Microsystems, Inc.
 *      Portions Copyright 2014 ForgeRock AS
 */
package org.opends.server.schema;

import org.forgerock.opendj.ldap.Assertion;
import org.forgerock.opendj.ldap.ByteString;
import org.forgerock.opendj.ldap.ConditionResult;
import org.forgerock.opendj.ldap.DecodeException;
import org.opends.server.api.OrderingMatchingRule;
import org.opends.server.core.DirectoryServer;
import org.opends.server.types.AcceptRejectWarn;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static org.testng.Assert.*;

/**
 * Test The Ordering matching rules and the Ordering matching rule api.
 */
public abstract class OrderingMatchingRuleTest extends SchemaTestCase
{
  /**
   * Create data for the OrderingMatchingRules test.
   *
   * @return The data for the OrderingMatchingRules test.
   */
  @DataProvider(name="Orderingmatchingrules")
  public abstract Object[][] createOrderingMatchingRuleTestData();


  /**
   * Test the comparison of valid values.
   */
  @Test(dataProvider= "Orderingmatchingrules")
  public void testAssertionMatches(String attributeValue, String assertionValue, int expectedResult)
         throws Exception
  {
    // Make sure that the specified class can be instantiated as a task.
    OrderingMatchingRule ruleInstance = getRule();

    // we should call initializeMatchingRule but they all seem empty at the moment.
    // ruleInstance.initializeMatchingRule(configEntry);

    ByteString normalizedAttrValue = ruleInstance.normalizeAttributeValue(ByteString.valueOf(attributeValue));

    Assertion assert1 = ruleInstance.getAssertion(ByteString.valueOf(assertionValue));
    ConditionResult result = assert1.matches(normalizedAttrValue);
    assertEquals(result.toBoolean(), expectedResult < 0);

    Assertion assert2 = ruleInstance.getLessOrEqualAssertion(ByteString.valueOf(assertionValue));
    ConditionResult result2 = assert2.matches(normalizedAttrValue);
    assertEquals(result2.toBoolean(), expectedResult <= 0);

    Assertion assert3 = ruleInstance.getGreaterOrEqualAssertion(ByteString.valueOf(assertionValue));
    ConditionResult result3 = assert3.matches(normalizedAttrValue);
    assertEquals(result3.toBoolean(), expectedResult >= 0);

  }

  /**
   * Test the comparison of valid values.
   */
  @Test(dataProvider= "Orderingmatchingrules")
  public void testComparison(String value1, String value2, int result) throws Exception
  {
    OrderingMatchingRule rule = getRule();

    // we should call initializeMatchingRule but they all seem empty at the moment.
    // ruleInstance.initializeMatchingRule(configEntry);

    ByteString normalizedValue1 = rule.normalizeAttributeValue(ByteString.valueOf(value1));
    ByteString normalizedValue2 = rule.normalizeAttributeValue(ByteString.valueOf(value2));
    int res = rule.comparator().compare(normalizedValue1, normalizedValue2);
    if (result == 0)
    {
      if (res != 0)
      {
        fail(rule + ".compareValues should return 0 for values " +
            value1 + " and " + value2);
      }
    }
    else if (result > 0)
    {
      if (res <= 0)
      {
        fail(rule + ".compareValues should return a positive integer "
            + "for values : " + value1 + " and " + value2);
      }
    }
    else
    {
      if (res >= 0)
      {
        fail(rule + ".compareValues should return a negative integer "
            + "for values : " + value1 + " and " + value2);
      }
    }
  }

  /**
   * Get the Ordering matching Rules that is to be tested.
   *
   * @return The Ordering matching Rules that is to be tested.
   */
  protected abstract OrderingMatchingRule getRule();


  /**
   * Create data for the OrderingMatchingRulesInvalidValues test.
   *
   * @return The data for the OrderingMatchingRulesInvalidValues test.
   */
  @DataProvider(name="OrderingMatchingRuleInvalidValues")
  public abstract Object[][] createOrderingMatchingRuleInvalidValues();


  /**
   * Test that invalid values are rejected.
   */
  @Test(dataProvider= "OrderingMatchingRuleInvalidValues")
  public void orderingMatchingRulesInvalidValues(String value) throws Exception
  {
    // Make sure that the specified class can be instantiated as a task.
    OrderingMatchingRule ruleInstance = getRule();

    // normalize the 2 provided values
    try
    {
      ruleInstance.normalizeAttributeValue(ByteString.valueOf(value));
    } catch (DecodeException e) {
      // that's the expected path : the matching rule has detected that
      // the value is incorrect.
      return;
    }
    // if we get there with false value for  success then the tested
    // matching rule did not raised the Exception.

    fail(ruleInstance + " did not catch that value " + value + " is invalid.");
  }

  /**
   * Test that invalid values are rejected.
   */
  @Test(dataProvider= "OrderingMatchingRuleInvalidValues")
  public void orderingMatchingRulesInvalidValuesWarn(String value)
         throws Exception
  {
    // Make sure that the specified class can be instantiated as a task.
    OrderingMatchingRule ruleInstance = getRule();

    AcceptRejectWarn accept = DirectoryServer.getSyntaxEnforcementPolicy();
    DirectoryServer.setSyntaxEnforcementPolicy(AcceptRejectWarn.WARN);
    // normalize the 2 provided values
    try
    {
      ruleInstance.normalizeAttributeValue(ByteString.valueOf(value));
    } catch (Exception e)
    {
      fail(ruleInstance + " in warn mode should not reject value " + value + e);
      return;
    }
    finally
    {
      DirectoryServer.setSyntaxEnforcementPolicy(accept);
    }
  }

}
