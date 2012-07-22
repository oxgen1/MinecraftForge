package cpw.mods.fml.common;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import argo.jdom.JdomParser;
import argo.jdom.JsonNode;
import argo.jdom.JsonRootNode;
import argo.saj.InvalidSyntaxException;

import com.google.common.base.Throwables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

public class MetadataCollection
{
    private static Logger log = Loader.log;
    private static JdomParser parser = new JdomParser();
    private Map<String, ModMetadata> metadatas = Maps.newHashMap();
    private int metadataVersion = 1;

    public static MetadataCollection from(InputStream inputStream)
    {
        InputStreamReader reader = new InputStreamReader(inputStream);
        try
        {
            JsonRootNode root = parser.parse(reader);
            if (root.hasElements())
            {
                return parse10ModInfo(root);
            }
            else
            {
                return parseModInfo(root);
            }
        }
        catch (Exception e)
        {
            throw Throwables.propagate(e);
        }
    }

    private static MetadataCollection parseModInfo(JsonRootNode root)
    {
        MetadataCollection mc = new MetadataCollection();
        mc.metadataVersion = Integer.parseInt(root.getNumberValue("modinfoversion"));
        mc.parseModMetadataList(root.getNode("modlist"));
        return mc;
    }

    private static MetadataCollection parse10ModInfo(JsonRootNode root)
    {
        MetadataCollection mc = new MetadataCollection();
        mc.parseModMetadataList(root);
        return mc;
    }

    private void parseModMetadataList(JsonNode metadataList)
    {
        for (JsonNode node : metadataList.getElements())
        {
            ModMetadata mmd = new ModMetadata(node);
            metadatas.put(mmd.modId, mmd);
        }
    }

    public ModMetadata getMetadataForId(String modId)
    {
        return metadatas.get(modId);
    }

}
