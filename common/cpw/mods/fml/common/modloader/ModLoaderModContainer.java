/*
 * The FML Forge Mod Loader suite.
 * Copyright (C) 2012 cpw
 *
 * This library is free software; you can redistribute it and/or modify it under the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or any later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along with this library; if not, write to the Free Software Foundation, Inc., 51
 * Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 */
package cpw.mods.fml.common.modloader;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.AbstractCollection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Level;

import com.google.common.base.Throwables;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.IConsoleHandler;
import cpw.mods.fml.common.ICraftingHandler;
import cpw.mods.fml.common.IDispenseHandler;
import cpw.mods.fml.common.IFMLSidedHandler;
import cpw.mods.fml.common.IKeyHandler;
import cpw.mods.fml.common.INetworkHandler;
import cpw.mods.fml.common.IPickupNotifier;
import cpw.mods.fml.common.IPlayerTracker;
import cpw.mods.fml.common.IWorldGenerator;
import cpw.mods.fml.common.LoadController;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.LoaderException;
import cpw.mods.fml.common.LoaderState;
import cpw.mods.fml.common.ModClassLoader;
import cpw.mods.fml.common.LoaderState.ModState;
import cpw.mods.fml.common.MetadataCollection;
import cpw.mods.fml.common.discovery.ContainerType;
import cpw.mods.fml.common.event.FMLConstructionEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.ModContainer;
import cpw.mods.fml.common.ModMetadata;
import cpw.mods.fml.common.ProxyInjector;
import cpw.mods.fml.common.TickType;

public class ModLoaderModContainer implements ModContainer
{
    private static final ProxyInjector NULLPROXY = new ProxyInjector("","","",null);
    private Class <? extends BaseMod > modClazz;
    public BaseMod mod;
    private File modSource;
    public ArrayList<String> requirements;
    public ArrayList<String> dependencies;
    public ArrayList<String> dependants;
    private ArrayList<IKeyHandler> keyHandlers;
    private ContainerType sourceType;
    private ModMetadata metadata;
    private ProxyInjector sidedProxy;
    private BaseModTicker gameTickHandler;
    private BaseModTicker guiTickHandler;
    private String modClazzName;
    private String modId;
    private EventBus bus;
    private LoadController controller;
    private boolean enabled;

    public ModLoaderModContainer(String className, File modSource)
    {
        this.modClazzName = className;
        this.modSource = modSource;
        this.modId = className.contains(".") ? className.substring(className.lastIndexOf('.')) : className;
    }

    /**
     * We only instantiate this for "not mod mods"
     * @param instance
     */
    ModLoaderModContainer(BaseMod instance) {
        FMLCommonHandler.instance().addAuxilliaryModContainer(this);
        this.mod=instance;
        this.gameTickHandler = new BaseModTicker(instance, false);
        this.guiTickHandler = new BaseModTicker(instance, true);
    }

    
    @Subscribe
    public void constructMod(FMLConstructionEvent event)
    {
        try 
        {
            ModClassLoader modClassLoader = event.getModClassLoader();
            modClassLoader.addFile(modSource);
            mod = (BaseMod)Class.forName(modClazzName, true, modClassLoader).newInstance();
        }
        catch (Exception e)
        {
            controller.errorOccurred(this, e);
            Throwables.propagateIfPossible(e);
        }
    }
    
    @Subscribe
    public void preInit(FMLPreInitializationEvent event)
    {
        try
        {
            EnumSet<TickType> ticks = EnumSet.noneOf(TickType.class);
            this.gameTickHandler = new BaseModTicker(ticks, false);
            this.guiTickHandler = new BaseModTicker(ticks.clone(), true);
            configureMod();
            mod = modClazz.newInstance();
            this.gameTickHandler.setMod(mod);
            this.guiTickHandler.setMod(mod);
            FMLCommonHandler.instance().registerTickHandler(this.gameTickHandler);
            FMLCommonHandler.instance().registerTickHandler(this.guiTickHandler);
            FMLCommonHandler.instance().registerWorldGenerator(this.mod);
        }
        catch (Exception e)
        {
            throw new LoaderException(e);
        }
    }
    /**
     *
     */
    private void configureMod()
    {
        IFMLSidedHandler sideHandler = FMLCommonHandler.instance().getSidedDelegate();
        File configDir = Loader.instance().getConfigDir();
        String modConfigName = modClazz.getSimpleName();
        File modConfig = new File(configDir, String.format("%s.cfg", modConfigName));
        Properties props = new Properties();

        boolean existingConfigFound = false;
        boolean mlPropFound = false;

        if (modConfig.exists())
        {
            try
            {
                Loader.log.fine(String.format("Reading existing configuration file for %s : %s", modConfigName, modConfig.getName()));
                FileReader configReader = new FileReader(modConfig);
                props.load(configReader);
                configReader.close();
            }
            catch (Exception e)
            {
                Loader.log.severe(String.format("Error occured reading mod configuration file %s", modConfig.getName()));
                Loader.log.throwing("ModLoaderModContainer", "configureMod", e);
                throw new LoaderException(e);
            }
            existingConfigFound = true;
        }

        StringBuffer comments = new StringBuffer();
        comments.append("MLProperties: name (type:default) min:max -- information\n");

        try
        {
            for (Field f : modClazz.getDeclaredFields())
            {
                if (!Modifier.isStatic(f.getModifiers()))
                {
                    continue;
                }

                ModProperty property = sideHandler.getModLoaderPropertyFor(f);
                if (property == null)
                {
                    continue;
                }
                String propertyName = property.name().length() > 0 ? property.name() : f.getName();
                String propertyValue = null;
                Object defaultValue = null;

                try
                {
                    defaultValue = f.get(null);
                    propertyValue = props.getProperty(propertyName, extractValue(defaultValue));
                    Object currentValue = parseValue(propertyValue, property, f.getType(), propertyName, modConfigName);
                    Loader.log.finest(String.format("Configuration for %s.%s found values default: %s, configured: %s, interpreted: %s", modConfigName, propertyName, defaultValue, propertyValue, currentValue));

                    if (currentValue != null && !currentValue.equals(defaultValue))
                    {
                        Loader.log.finest(String.format("Configuration for %s.%s value set to: %s", modConfigName, propertyName, currentValue));
                        f.set(null, currentValue);
                    }
                }
                catch (Exception e)
                {
                    Loader.log.severe(String.format("Invalid configuration found for %s in %s", propertyName, modConfig.getName()));
                    Loader.log.throwing("ModLoaderModContainer", "configureMod", e);
                    throw new LoaderException(e);
                }
                finally
                {
                    comments.append(String.format("MLProp : %s (%s:%s", propertyName, f.getType().getName(), defaultValue));

                    if (property.min() != Double.MIN_VALUE)
                    {
                        comments.append(",>=").append(String.format("%.1f", property.min()));
                    }

                    if (property.max() != Double.MAX_VALUE)
                    {
                        comments.append(",<=").append(String.format("%.1f", property.max()));
                    }

                    comments.append(")");

                    if (property.info().length() > 0)
                    {
                        comments.append(" -- ").append(property.info());
                    }

                    if (propertyValue != null)
                    {
                        props.setProperty(propertyName, extractValue(propertyValue));
                    }
                    comments.append("\n");
                }
                mlPropFound = true;
            }
        }
        finally
        {
            if (!mlPropFound && !existingConfigFound)
            {
                Loader.log.fine(String.format("No MLProp configuration for %s found or required. No file written", modConfigName));
                return;
            }

            if (!mlPropFound && existingConfigFound)
            {
                File mlPropBackup = new File(modConfig.getParent(),modConfig.getName()+".bak");
                Loader.log.fine(String.format("MLProp configuration file for %s found but not required. Attempting to rename file to %s", modConfigName, mlPropBackup.getName()));
                boolean renamed = modConfig.renameTo(mlPropBackup);
                if (renamed)
                {
                    Loader.log.fine(String.format("Unused MLProp configuration file for %s renamed successfully to %s", modConfigName, mlPropBackup.getName()));
                }
                else
                {
                    Loader.log.fine(String.format("Unused MLProp configuration file for %s renamed UNSUCCESSFULLY to %s", modConfigName, mlPropBackup.getName()));
                }

                return;
            }
            try
            {
                FileWriter configWriter = new FileWriter(modConfig);
                props.store(configWriter, comments.toString());
                configWriter.close();
                Loader.log.fine(String.format("Configuration for %s written to %s", modConfigName, modConfig.getName()));
            }
            catch (IOException e)
            {
                Loader.log.warning(String.format("Error trying to write the config file %s", modConfig.getName()));
                Loader.log.throwing("ModLoaderModContainer", "configureMod", e);
                throw new LoaderException(e);
            }
        }
    }

    private Object parseValue(String val, ModProperty property, Class<?> type, String propertyName, String modConfigName)
    {
        if (type.isAssignableFrom(String.class))
        {
            return (String)val;
        }
        else if (type.isAssignableFrom(Boolean.TYPE) || type.isAssignableFrom(Boolean.class))
        {
            return Boolean.parseBoolean(val);
        }
        else if (Number.class.isAssignableFrom(type) || type.isPrimitive())
        {
            Number n = null;

            if (type.isAssignableFrom(Double.TYPE) || Double.class.isAssignableFrom(type))
            {
                n = Double.parseDouble(val);
            }
            else if (type.isAssignableFrom(Float.TYPE) || Float.class.isAssignableFrom(type))
            {
                n = Float.parseFloat(val);
            }
            else if (type.isAssignableFrom(Long.TYPE) || Long.class.isAssignableFrom(type))
            {
                n = Long.parseLong(val);
            }
            else if (type.isAssignableFrom(Integer.TYPE) || Integer.class.isAssignableFrom(type))
            {
                n = Integer.parseInt(val);
            }
            else if (type.isAssignableFrom(Short.TYPE) || Short.class.isAssignableFrom(type))
            {
                n = Short.parseShort(val);
            }
            else if (type.isAssignableFrom(Byte.TYPE) || Byte.class.isAssignableFrom(type))
            {
                n = Byte.parseByte(val);
            }
            else
            {
                throw new IllegalArgumentException(String.format("MLProp declared on %s of type %s, an unsupported type",propertyName, type.getName()));
            }

            if (n.doubleValue() < property.min() || n.doubleValue() > property.max())
            {
                Loader.log.warning(String.format("Configuration for %s.%s found value %s outside acceptable range %s,%s", modConfigName,propertyName, n, property.min(), property.max()));
                return null;
            }
            else
            {
                return n;
            }
        }

        throw new IllegalArgumentException(String.format("MLProp declared on %s of type %s, an unsupported type",propertyName, type.getName()));
    }
    private String extractValue(Object value)
    {
        if (String.class.isInstance(value))
        {
            return (String)value;
        }
        else if (Number.class.isInstance(value) || Boolean.class.isInstance(value))
        {
            return String.valueOf(value);
        }
        else
        {
            throw new IllegalArgumentException("MLProp declared on non-standard type");
        }
    }

    @Override
    public String getName()
    {
        return mod != null ? mod.getName() : modClazz.getSimpleName();
    }

    @Deprecated
    public static ModContainer findContainerFor(BaseMod mod)
    {
        return FMLCommonHandler.instance().findContainerFor(mod);
    }

    @Override
    public String getSortingRules()
    {
        if (mod!=null) {
            return mod.getPriorities();
        } else {
            return "";
        }
    }
    @Override
    public boolean matches(Object mod)
    {
        return modClazz.isInstance(mod);
    }

    /**
     * Find all the BaseMods in the system
     * @param <A>
     * @return
     */
    public static <A extends BaseMod> List<A> findAll(Class<A> clazz)
    {
        ArrayList<A> modList = new ArrayList<A>();

        for (ModContainer mc : Loader.getModList())
        {
            if (mc instanceof ModLoaderModContainer && mc.getMod()!=null)
            {
                modList.add((A)((ModLoaderModContainer)mc).mod);
            }
        }

        return modList;
    }

    @Override
    public File getSource()
    {
        return modSource;
    }

    @Override
    public Object getMod()
    {
        return mod;
    }

    @Override
    public List<String> getRequirements()
    {
        return requirements;
    }

    @Override
    public List<String> getDependants()
    {
        return dependants;
    }

    @Override
    public List<String> getDependencies()
    {
        return dependencies;
    }


    public String toString()
    {
        return modClazz.getSimpleName();
    }

    /**
     * @param keyHandler
     * @param allowRepeat
     */
    public void addKeyHandler(IKeyHandler handler)
    {
        if (keyHandlers==null) {
            keyHandlers=new ArrayList<IKeyHandler>();
        }

        Iterator<IKeyHandler> itr = keyHandlers.iterator();
        while(itr.hasNext())
        {
            IKeyHandler old = itr.next();
            if (old.getKeyBinding() == handler.getKeyBinding())
            {
                itr.remove();
            }
        }

        keyHandlers.add(handler);
    }

    @Override
    public ModMetadata getMetadata()
    {
        return metadata;
    }

    @Override
    public String getVersion()
    {
        if (mod == null || mod.getVersion() == null)
        {
            return "Not available";
        }
        return mod.getVersion();
    }

    @Override
    public ProxyInjector findSidedProxy()
    {
        if (sidedProxy==null) {
            sidedProxy = FMLCommonHandler.instance().getSidedDelegate().findSidedProxyOn(mod);
            if (sidedProxy == null)
            {
                sidedProxy = NULLPROXY;
            }
        }
        return sidedProxy == NULLPROXY ? null : sidedProxy;
    }

    /**
     * @return
     */
    public BaseModTicker getGameTickHandler()
    {
        return this.gameTickHandler;
    }
    /**
     * @return
     */
    public BaseModTicker getGUITickHandler()
    {
        return this.guiTickHandler;
    }

    @Override
    public String getModId()
    {
        return modId;
    }

    @Override
    public void bindMetadata(MetadataCollection mc)
    {
        this.metadata = mc.getMetadataForId(modId);
    }

    @Override
    public void setEnabledState(boolean enabled)
    {
        this.enabled = enabled;
    }

    @Override
    public boolean registerBus(EventBus bus, LoadController controller)
    {
        if (this.enabled)
        {
            controller.log(Level.FINE, "Enabling mod %s", getModId());
            this.bus = bus;
            this.controller = controller;
            bus.register(this);
            return true;
        }
        else
        {
            return false;
        }
    }
}
