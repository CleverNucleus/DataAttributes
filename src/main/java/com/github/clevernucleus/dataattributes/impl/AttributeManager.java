package com.github.clevernucleus.dataattributes.impl;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import net.minecraft.registry.Registries;
import org.slf4j.Logger;

import com.github.clevernucleus.dataattributes.api.DataAttributesAPI;
import com.github.clevernucleus.dataattributes.api.event.AttributesReloadedEvent;
import com.github.clevernucleus.dataattributes.json.AttributeFunctionJson;
import com.github.clevernucleus.dataattributes.json.AttributeOverrideJson;
import com.github.clevernucleus.dataattributes.json.EntityTypesJson;
import com.github.clevernucleus.dataattributes.json.FunctionsJson;
import com.github.clevernucleus.dataattributes.json.PropertiesJson;
import com.github.clevernucleus.dataattributes.mutable.MutableEntityAttribute;
import com.google.common.collect.ImmutableMap;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.logging.LogUtils;

import net.fabricmc.fabric.api.resource.SimpleResourceReloadListener;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.DefaultAttributeRegistry;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.StringNbtReader;
import net.minecraft.nbt.visitor.StringNbtWriter;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import net.minecraft.util.JsonHelper;
import net.minecraft.util.profiler.Profiler;

public final class AttributeManager implements SimpleResourceReloadListener<AttributeManager.Wrapper> {
	private static final Gson GSON = (new GsonBuilder()).excludeFieldsWithoutExposeAnnotation().create();
	private static final int PATH_SUFFIX_LENGTH = ".json".length();
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final String DIRECTORY = "attributes";
	private static final Identifier ID = new Identifier(DataAttributesAPI.MODID, DIRECTORY);

	private Map<Identifier, EntityAttributeData> entityAttributeData = ImmutableMap.of();
	private Map<Identifier, EntityTypeData> entityTypeData = ImmutableMap.of();
	public Map<EntityType<? extends LivingEntity>, DefaultAttributeContainer> containers = ImmutableMap.of();
	private byte[] entityAttributeBytes, entityTypeBytes;

	protected record Wrapper(Map<Identifier, EntityAttributeData> entityAttributeData, Map<Identifier, EntityTypeData> entityTypeData) {}

	public AttributeManager() {}

	private static Map<Identifier, AttributeFunctionJson> formatFunctions(Map<String, AttributeFunctionJson> functionsIn) {
		Map<Identifier, AttributeFunctionJson> functions = new HashMap<Identifier, AttributeFunctionJson>();

		for(String key : functionsIn.keySet()) {
			AttributeFunctionJson value = functionsIn.get(key);

			functions.put(new Identifier(key), value);
		}

		return functions;
	}

	private static EntityAttribute getOrCreate(final Identifier identifier, EntityAttribute attributeIn) {
		EntityAttribute attribute = Registries.ATTRIBUTE.get(identifier);

		if(attribute == null) {
			attribute = MutableRegistryImpl.register(Registries.ATTRIBUTE, identifier, attributeIn);
		}

		return attribute;
	}

	private static void loadOverrides(ResourceManager manager, Map<Identifier, EntityAttributeData> entityAttributeData) {
		Map<Identifier, AttributeOverrideJson> cache = new HashMap<Identifier, AttributeOverrideJson>();
		String location = DIRECTORY + "/overrides";
		int length = location.length() + 1;

		for(Map.Entry<Identifier, Resource> entry : manager.findResources(location, id -> id.getPath().endsWith(".json")).entrySet()) {
			Identifier resource = entry.getKey();
			String path = resource.getPath();
			Identifier identifier = new Identifier(resource.getNamespace(), path.substring(length, path.length() - PATH_SUFFIX_LENGTH));

			try {
				BufferedReader reader = entry.getValue().getReader();

				try {
					AttributeOverrideJson json = JsonHelper.deserialize(GSON, (Reader)reader, AttributeOverrideJson.class);

					if(json != null) {
						AttributeOverrideJson object = cache.put(identifier, json);

						if(object == null) continue;
						throw new IllegalStateException("Duplicate data file ignored with ID " + identifier);
					}

					LOGGER.error("Couldn't load data file {} from {} as it's null or empty", (Object)identifier, (Object)resource);
				} finally {
					if(reader == null) continue;
					((Reader)reader).close();
				}
			} catch(IOException | IllegalArgumentException exception) {
				LOGGER.error("Couldn't parse data file {} from {}", identifier, resource, exception);
			}
		}

		cache.forEach((key, value) -> entityAttributeData.put(key, new EntityAttributeData(value)));
	}

	private static void loadFunctions(ResourceManager manager, Map<Identifier, EntityAttributeData> entityAttributeData) {
		Map<Identifier, FunctionsJson> cache = new HashMap<Identifier, FunctionsJson>();
		int length = DIRECTORY.length() + 1;

		for(Map.Entry<Identifier, Resource> entry : manager.findResources(DIRECTORY, id -> id.getPath().endsWith("functions.json")).entrySet()) {
			Identifier resource = entry.getKey();
			String path = resource.getPath();
			Identifier identifier = new Identifier(resource.getNamespace(), path.substring(length, path.length() - PATH_SUFFIX_LENGTH));

			try {
				BufferedReader reader = entry.getValue().getReader();

				try {
					FunctionsJson json = JsonHelper.deserialize(GSON, (Reader)reader, FunctionsJson.class);

					if(json != null) {
						FunctionsJson object = cache.put(identifier, json);

						if(object == null) continue;
						throw new IllegalStateException("Duplicate data file ignored with ID " + identifier);
					}

					LOGGER.error("Couldn't load data file {} from {} as it's null or empty", (Object)identifier, (Object)resource);
				} finally {
					if(reader == null) continue;
					((Reader)reader).close();
				}
			} catch(IOException | IllegalArgumentException exception) {
				LOGGER.error("Couldn't parse data file {} from {}", identifier, resource, exception);
			}
		}

		Map<String, Map<String, AttributeFunctionJson>> functions = new HashMap<String, Map<String, AttributeFunctionJson>>();
		cache.values().forEach(json -> json.merge(functions));

		for(String key : functions.keySet()) {
			Identifier identifier = new Identifier(key);
			EntityAttributeData data = entityAttributeData.getOrDefault(identifier, new EntityAttributeData());
			data.putFunctions(formatFunctions(functions.get(key)));
			entityAttributeData.put(identifier, data);
		}
	}

	private static void loadProperties(ResourceManager manager, Map<Identifier, EntityAttributeData> entityAttributeData) {
		Map<Identifier, PropertiesJson> cache = new HashMap<Identifier, PropertiesJson>();
		int length = DIRECTORY.length() + 1;

		for(Map.Entry<Identifier, Resource> entry : manager.findResources(DIRECTORY, id -> id.getPath().endsWith("properties.json")).entrySet()) {
			Identifier resource = entry.getKey();
			String path = resource.getPath();
			Identifier identifier = new Identifier(resource.getNamespace(), path.substring(length, path.length() - PATH_SUFFIX_LENGTH));

			try {
				BufferedReader reader = entry.getValue().getReader();

				try {
					PropertiesJson json = JsonHelper.deserialize(GSON, (Reader)reader, PropertiesJson.class);

					if(json != null) {
						PropertiesJson object = cache.put(identifier, json);

						if(object == null) continue;
						throw new IllegalStateException("Duplicate data file ignored with ID " + identifier);
					}

					LOGGER.error("Couldn't load data file {} from {} as it's null or empty", (Object)identifier, (Object)resource);
				} finally {
					if(reader == null) continue;
					((Reader)reader).close();
				}
			} catch(IOException | IllegalArgumentException exception) {
				LOGGER.error("Couldn't parse data file {} from {}", identifier, resource, exception);
			}
		}

		Map<String, Map<String, String>> properties = new HashMap<String, Map<String, String>>();
		cache.values().forEach(json -> json.merge(properties));

		for(String key : properties.keySet()) {
			Identifier identifier = new Identifier(key);
			EntityAttributeData data = entityAttributeData.getOrDefault(identifier, new EntityAttributeData());
			data.putProperties(properties.get(key));
			entityAttributeData.put(identifier, data);
		}
	}

	private static void loadEntityTypes(ResourceManager manager, Map<Identifier, EntityTypeData> entityTypeData) {
		Map<Identifier, EntityTypesJson> cache = new HashMap<Identifier, EntityTypesJson>();
		int length = DIRECTORY.length() + 1;

		for(Map.Entry<Identifier, Resource> entry : manager.findResources(DIRECTORY, id -> id.getPath().endsWith("entity_types.json")).entrySet()) {
			Identifier resource = entry.getKey();
			String path = resource.getPath();
			Identifier identifier = new Identifier(resource.getNamespace(), path.substring(length, path.length() - PATH_SUFFIX_LENGTH));

			try {
				BufferedReader reader = entry.getValue().getReader();

				try {
					EntityTypesJson json = JsonHelper.deserialize(GSON, (Reader)reader, EntityTypesJson.class);

					if(json != null) {
						EntityTypesJson object = cache.put(identifier, json);

						if(object == null) continue;
						throw new IllegalStateException("Duplicate data file ignored with ID " + identifier);
					}

					LOGGER.error("Couldn't load data file {} from {} as it's null or empty", (Object)identifier, (Object)resource);
				} finally {
					if(reader == null) continue;
					((Reader)reader).close();
				}
			} catch(IOException | IllegalArgumentException exception) {
				LOGGER.error("Couldn't parse data file {} from {}", identifier, resource, exception);
			}
		}

		Map<String, Map<String, Double>> entityTypes = new HashMap<String, Map<String, Double>>();
		cache.values().forEach(json -> json.merge(entityTypes));

		for(String key : entityTypes.keySet()) {
			Identifier identifier = new Identifier(key);
			EntityTypeData data = new EntityTypeData(entityTypes.get(key));
			entityTypeData.put(identifier, data);
		}
	}

	private <T extends NbtIO> byte[] generateCurrentData(final Map<Identifier, T> data) {
		StringNbtWriter writer = new StringNbtWriter();
		NbtCompound tag = new NbtCompound();

		data.forEach((key, value) -> {
			NbtCompound entry = new NbtCompound();
			value.writeToNbt(entry);
			tag.put(key.toString(), entry);
		});

		String snbt = writer.apply(tag);
		byte[] bytes;

		try {
			bytes = snbt.getBytes("UTF-8");
		} catch(UnsupportedEncodingException e) {
			bytes = new byte[] {(byte)0};
		}

		Deflater deflater = new Deflater();
		deflater.setInput(bytes);
		deflater.finish();

		byte[] compressed = new byte[Short.MAX_VALUE];
		int size = deflater.deflate(compressed);
		deflater.end();

		return Arrays.copyOf(compressed, size);
	}


	private NbtCompound readFromData(byte[] bytesIn) {
		Inflater inflater = new Inflater();
		inflater.setInput(bytesIn);

		byte[] cache = new byte[Short.MAX_VALUE];
		int size;

		try {
			size = inflater.inflate(cache);
		} catch (DataFormatException e) {
			size = 1;
		}

		inflater.end();
		byte[] uncompressed = Arrays.copyOf(cache, size);
		String snbt = new String(uncompressed);
		NbtCompound nbt;

		try {
			nbt = StringNbtReader.parse(snbt);
		} catch (CommandSyntaxException e) {
			nbt = new NbtCompound();
		}

		return nbt;
	}

	public void setEntityAttributeData(byte[] bytesIn) {
		NbtCompound tag = this.readFromData(bytesIn);
		ImmutableMap.Builder<Identifier, EntityAttributeData> builder = ImmutableMap.builder();
		tag.getKeys().forEach(key -> {
			NbtCompound entry = tag.getCompound(key);
			EntityAttributeData entityAttributeData = new EntityAttributeData();
			entityAttributeData.readFromNbt(entry);
			builder.put(new Identifier(key), entityAttributeData);
		});

		this.entityAttributeData = builder.build();
	}

	public void setEntityTypeData(byte[] bytesIn) {
		NbtCompound tag = this.readFromData(bytesIn);
		ImmutableMap.Builder<Identifier, EntityTypeData> builder = ImmutableMap.builder();
		tag.getKeys().forEach(key -> {
			NbtCompound entry = tag.getCompound(key);
			EntityTypeData entityTypeData = new EntityTypeData();
			entityTypeData.readFromNbt(entry);
			builder.put(new Identifier(key), entityTypeData);
		});

		this.entityTypeData = builder.build();
	}

	public byte[] getEntityAttributeData() {
		if(this.entityAttributeBytes == null) return new byte[] {(byte)0};
		return this.entityAttributeBytes;
	}

	public byte[] getEntityTypeData() {
		if(this.entityTypeBytes == null) return new byte[] {(byte)0};
		return this.entityTypeBytes;
	}

	public DefaultAttributeContainer getContainer(EntityType<? extends LivingEntity> entityType) {
		return this.containers.getOrDefault(entityType, DefaultAttributeRegistry.get(entityType));
	}

	@SuppressWarnings("unchecked")
	public void apply() {
		MutableRegistryImpl.unregister(Registries.ATTRIBUTE);

		for(Identifier identifier : Registries.ATTRIBUTE.getIds()) {
			EntityAttribute entityAttribute = Registries.ATTRIBUTE.get(identifier);

			if(entityAttribute == null) continue;

			((MutableEntityAttribute)entityAttribute).clear();
		}

		for(Identifier identifier : this.entityAttributeData.keySet()) {
			EntityAttributeData entityAttributeData = this.entityAttributeData.get(identifier);
			entityAttributeData.override(identifier, AttributeManager::getOrCreate);
		}

		for(Identifier identifier : this.entityAttributeData.keySet()) {
			EntityAttribute entityAttribute = Registries.ATTRIBUTE.get(identifier);

			if(entityAttribute == null) continue;

			EntityAttributeData entityAttributeData = this.entityAttributeData.get(identifier);
			entityAttributeData.copy(entityAttribute);
		}

		Collection<Identifier> entityTypes = Registries.ENTITY_TYPE.getIds().stream().filter(id -> DefaultAttributeRegistry.hasDefinitionFor(Registries.ENTITY_TYPE.get(id))).collect(Collectors.toSet());
		ImmutableMap.Builder<EntityType<? extends LivingEntity>, DefaultAttributeContainer> containers = ImmutableMap.builder();

		for(Identifier identifier : this.entityTypeData.keySet()) {
			if(!entityTypes.contains(identifier)) continue;


			EntityType<? extends LivingEntity> entityType = (EntityType<? extends LivingEntity>)Registries.ENTITY_TYPE.get(identifier);
			DefaultAttributeContainer.Builder builder = DefaultAttributeContainer.builder();
			EntityTypeData entityTypeData = this.entityTypeData.get(identifier);
			entityTypeData.build(builder, DefaultAttributeRegistry.get(entityType));
			containers.put(entityType, builder.build());
		}

		this.containers = containers.build();

		AttributesReloadedEvent.EVENT.invoker().onCompletedReload();
	}

	@Override
	public CompletableFuture<AttributeManager.Wrapper> load(ResourceManager manager, Profiler profiler, Executor executor) {
		return CompletableFuture.supplyAsync(() -> {
			Map<Identifier, EntityAttributeData> entityAttributeData = new HashMap<Identifier, EntityAttributeData>();
			loadOverrides(manager, entityAttributeData);
			loadFunctions(manager, entityAttributeData);
			loadProperties(manager, entityAttributeData);

			Map<Identifier, EntityTypeData> entityTypeData = new HashMap<Identifier, EntityTypeData>();
			loadEntityTypes(manager, entityTypeData);

			return new AttributeManager.Wrapper(entityAttributeData, entityTypeData);
		}, executor);
	}

	@Override
	public CompletableFuture<Void> apply(AttributeManager.Wrapper data, ResourceManager manager, Profiler profiler, Executor executor) {
		return CompletableFuture.runAsync(() -> {
			ImmutableMap.Builder<Identifier, EntityAttributeData> entityAttributeData = ImmutableMap.builder();
			data.entityAttributeData.forEach(entityAttributeData::put);
			this.entityAttributeData = entityAttributeData.build();
			this.entityAttributeBytes = this.generateCurrentData(this.entityAttributeData);

			ImmutableMap.Builder<Identifier, EntityTypeData> entityTypeData = ImmutableMap.builder();
			data.entityTypeData.forEach(entityTypeData::put);
			this.entityTypeData = entityTypeData.build();
			this.entityTypeBytes = this.generateCurrentData(this.entityTypeData);

			this.apply();
		}, executor);
	}

	@Override
	public Identifier getFabricId() {
		return ID;
	}
}
