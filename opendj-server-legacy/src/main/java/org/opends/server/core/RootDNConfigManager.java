/*
 * The contents of this file are subject to the terms of the Common Development and
 * Distribution License (the License). You may not use this file except in compliance with the
 * License.
 *
 * You can obtain a copy of the License at legal/CDDLv1.0.txt. See the License for the
 * specific language governing permission and limitations under the License.
 *
 * When distributing Covered Software, include this CDDL Header Notice in each file and include
 * the License file at legal/CDDLv1.0.txt. If applicable, add the following below the CDDL
 * Header, with the fields enclosed by brackets [] replaced by your own identifying
 * information: "Portions Copyright [year] [name of copyright owner]".
 *
 * Copyright 2006-2008 Sun Microsystems, Inc.
 * Portions Copyright 2014-2016 ForgeRock AS.
 */
package org.opends.server.core;

import static org.opends.messages.ConfigMessages.*;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.forgerock.i18n.LocalizableMessage;
import org.forgerock.opendj.config.server.ConfigException;
import org.forgerock.opendj.ldap.ResultCode;
import org.forgerock.opendj.config.server.ConfigurationAddListener;
import org.forgerock.opendj.config.server.ConfigurationChangeListener;
import org.forgerock.opendj.config.server.ConfigurationDeleteListener;
import org.forgerock.opendj.server.config.server.RootDNCfg;
import org.forgerock.opendj.server.config.server.RootDNUserCfg;
import org.forgerock.opendj.config.server.ConfigChangeResult;
import org.forgerock.opendj.ldap.DN;
import org.opends.server.types.DirectoryException;
import org.opends.server.types.InitializationException;
import org.opends.server.types.Privilege;

/**
 * This class defines a utility that will be used to manage the set of root
 * users defined in the Directory Server.  It will handle both the
 * "cn=Root DNs,cn=config" entry itself (through the root privilege change
 * listener), and all of its children.
 */
public class RootDNConfigManager
       implements ConfigurationChangeListener<RootDNUserCfg>,
                  ConfigurationAddListener<RootDNUserCfg>,
                  ConfigurationDeleteListener<RootDNUserCfg>
{
  /** A mapping between the actual root DNs and their alternate bind DNs. */
  private ConcurrentHashMap<DN,HashSet<DN>> alternateBindDNs;

  /**
   * The root privilege change listener that will handle changes to the
   * "cn=Root DNs,cn=config" entry itself.
   */
  private RootPrivilegeChangeListener rootPrivilegeChangeListener;

  private final ServerContext serverContext;

  /**
   * Creates a new instance of this root DN config manager.
   *
   * @param serverContext
   *          The server context.
   */
  public RootDNConfigManager(ServerContext serverContext)
  {
    this.serverContext = serverContext;
    alternateBindDNs = new ConcurrentHashMap<>();
    rootPrivilegeChangeListener = new RootPrivilegeChangeListener();
  }

  /**
   * Initializes all of the root users currently defined in the Directory Server
   * configuration, as well as the set of privileges that root users will
   * inherit by default.
   *
   * @throws ConfigException
   *           If a configuration problem causes the identity mapper
   *           initialization process to fail.
   * @throws InitializationException
   *           If a problem occurs while initializing the identity mappers that
   *           is not related to the server configuration.
   */
  public void initializeRootDNs()
         throws ConfigException, InitializationException
  {
    RootDNCfg rootDNCfg = serverContext.getRootConfig().getRootDN();
    rootPrivilegeChangeListener.setDefaultRootPrivileges(rootDNCfg);
    rootDNCfg.addChangeListener(rootPrivilegeChangeListener);

    rootDNCfg.addRootDNUserAddListener(this);
    rootDNCfg.addRootDNUserDeleteListener(this);

    // Get the set of root users defined below "cn=Root DNs,cn=config".  For
    // each one, register as a change listener, and get the set of alternate
    // bind DNs.
    for (String name : rootDNCfg.listRootDNUsers())
    {
      RootDNUserCfg rootUserCfg = rootDNCfg.getRootDNUser(name);
      rootUserCfg.addChangeListener(this);
      DirectoryServer.registerRootDN(rootUserCfg.dn());

      HashSet<DN> altBindDNs = new HashSet<>();
      for (DN alternateBindDN : rootUserCfg.getAlternateBindDN())
      {
        try
        {
          altBindDNs.add(alternateBindDN);
          DirectoryServer.registerAlternateRootDN(rootUserCfg.dn(),
                                                  alternateBindDN);
        }
        catch (DirectoryException de)
        {
          throw new InitializationException(de.getMessageObject());
        }
      }

      alternateBindDNs.put(rootUserCfg.dn(), altBindDNs);
    }
  }

  /**
   * Retrieves the set of privileges that will be granted to root users by
   * default.
   *
   * @return  The set of privileges that will be granted to root users by
   *          default.
   */
  public Set<Privilege> getRootPrivileges()
  {
    return rootPrivilegeChangeListener.getDefaultRootPrivileges();
  }

  @Override
  public boolean isConfigurationAddAcceptable(RootDNUserCfg configuration,
                                              List<LocalizableMessage> unacceptableReasons)
  {
    // The new root user must not have an alternate bind DN that is already
    // in use.
    boolean configAcceptable = true;
    for (DN altBindDN : configuration.getAlternateBindDN())
    {
      DN existingRootDN = DirectoryServer.getActualRootBindDN(altBindDN);
      if (existingRootDN != null)
      {
        unacceptableReasons.add(ERR_CONFIG_ROOTDN_CONFLICTING_MAPPING.get(
            altBindDN, configuration.dn(), existingRootDN));
        configAcceptable = false;
      }
    }

    return configAcceptable;
  }

  @Override
  public ConfigChangeResult applyConfigurationAdd(RootDNUserCfg configuration)
  {
    configuration.addChangeListener(this);

    final ConfigChangeResult ccr = new ConfigChangeResult();

    HashSet<DN> altBindDNs = new HashSet<>();
    for (DN altBindDN : configuration.getAlternateBindDN())
    {
      try
      {
        DirectoryServer.registerAlternateRootDN(configuration.dn(), altBindDN);
        altBindDNs.add(altBindDN);
      }
      catch (DirectoryException de)
      {
        // This shouldn't happen, since the set of DNs should have already been
        // validated.
        ccr.setResultCode(DirectoryServer.getCoreConfigManager().getServerErrorResultCode());
        ccr.addMessage(de.getMessageObject());

        for (DN dn : altBindDNs)
        {
          DirectoryServer.deregisterAlternateRootBindDN(dn);
        }
        break;
      }
    }

    if (ccr.getResultCode() == ResultCode.SUCCESS)
    {
      DirectoryServer.registerRootDN(configuration.dn());
      alternateBindDNs.put(configuration.dn(), altBindDNs);
    }

    return ccr;
  }

  @Override
  public boolean isConfigurationDeleteAcceptable(RootDNUserCfg configuration,
                      List<LocalizableMessage> unacceptableReasons)
  {
    return true;
  }

  @Override
  public ConfigChangeResult applyConfigurationDelete(
                                 RootDNUserCfg configuration)
  {
    DirectoryServer.deregisterRootDN(configuration.dn());
    configuration.removeChangeListener(this);

    final ConfigChangeResult ccr = new ConfigChangeResult();

    HashSet<DN> altBindDNs = alternateBindDNs.remove(configuration.dn());
    if (altBindDNs != null)
    {
      for (DN dn : altBindDNs)
      {
        DirectoryServer.deregisterAlternateRootBindDN(dn);
      }
    }

    return ccr;
  }

  @Override
  public boolean isConfigurationChangeAcceptable(RootDNUserCfg configuration,
                      List<LocalizableMessage> unacceptableReasons)
  {
    boolean configAcceptable = true;

    // There must not be any new alternate bind DNs that are already in use by
    // other root users.
    for (DN altBindDN: configuration.getAlternateBindDN())
    {
      DN existingRootDN = DirectoryServer.getActualRootBindDN(altBindDN);
      if (existingRootDN != null && !existingRootDN.equals(configuration.dn()))
      {
        unacceptableReasons.add(ERR_CONFIG_ROOTDN_CONFLICTING_MAPPING.get(
            altBindDN, configuration.dn(), existingRootDN));
        configAcceptable = false;
      }
    }

    return configAcceptable;
  }

  @Override
  public ConfigChangeResult applyConfigurationChange(
                                 RootDNUserCfg configuration)
  {
    final ConfigChangeResult ccr = new ConfigChangeResult();

    HashSet<DN> setDNs = new HashSet<>();
    HashSet<DN> addDNs = new HashSet<>();
    HashSet<DN> delDNs = new HashSet<>(alternateBindDNs.get(configuration.dn()));

    for (DN altBindDN : configuration.getAlternateBindDN())
    {
      setDNs.add(altBindDN);

      if (! delDNs.remove(altBindDN))
      {
        addDNs.add(altBindDN);
      }
    }

    for (DN dn : delDNs)
    {
      DirectoryServer.deregisterAlternateRootBindDN(dn);
    }

    HashSet<DN> addedDNs = new HashSet<>(addDNs.size());
    for (DN dn : addDNs)
    {
      try
      {
        DirectoryServer.registerAlternateRootDN(configuration.dn(), dn);
        addedDNs.add(dn);
      }
      catch (DirectoryException de)
      {
        // This shouldn't happen, since the set of DNs should have already been
        // validated.
        ccr.setResultCode(DirectoryServer.getCoreConfigManager().getServerErrorResultCode());
        ccr.addMessage(de.getMessageObject());

        for (DN addedDN : addedDNs)
        {
          DirectoryServer.deregisterAlternateRootBindDN(addedDN);
        }

        for (DN deletedDN : delDNs)
        {
          try
          {
            DirectoryServer.registerAlternateRootDN(configuration.dn(),
                                                    deletedDN);
          }
          catch (Exception e)
          {
            // This should also never happen.
            alternateBindDNs.get(configuration.dn()).remove(deletedDN);
          }
        }
      }
    }

    if (ccr.getResultCode() == ResultCode.SUCCESS)
    {
      alternateBindDNs.put(configuration.dn(), setDNs);
    }

    return ccr;
  }
}
