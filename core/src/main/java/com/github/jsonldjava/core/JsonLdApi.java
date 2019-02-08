package com.github.jsonldjava.core;

import static com.github.jsonldjava.core.JsonLdConsts.RDF_FIRST;
import static com.github.jsonldjava.core.JsonLdConsts.RDF_LIST;
import static com.github.jsonldjava.core.JsonLdConsts.RDF_NIL;
import static com.github.jsonldjava.core.JsonLdConsts.RDF_REST;
import static com.github.jsonldjava.core.JsonLdConsts.RDF_TYPE;
import static com.github.jsonldjava.core.JsonLdUtils.isKeyword;
import static com.github.jsonldjava.utils.Obj.newMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.github.jsonldjava.core.JsonLdError.Error;
import com.github.jsonldjava.utils.Obj;

/**
 * A container object to maintain state relating to JsonLdOptions and the
 * current Context, and push these into the relevant algorithms in
 * JsonLdProcessor as necessary.
 *
 * @author tristan
 */
public class JsonLdApi {

    private final Logger log = LoggerFactory.getLogger(this.getClass());

    JsonLdOptions opts;

    Object value = null;

    Context context = null;

    public JsonLdApi() {
        this(new JsonLdOptions(""));
    }

    public JsonLdApi(Object input, JsonLdOptions opts) throws JsonLdError {
        this(opts);
        initialize(input, null);
    }

    public JsonLdApi(Object input, Object context, JsonLdOptions opts) throws JsonLdError {
        this(opts);
        initialize(input, null);
    }

    public JsonLdApi(JsonLdOptions opts) {
        if (opts == null) {
            opts = new JsonLdOptions("");
        } else {
            this.opts = opts;
        }
    }

    /**
     * Initializes this object by cloning the input object using
     * {@link JsonLdUtils#clone(Object)}, and by parsing the context using
     * {@link Context#parse(Object)}.
     *
     * @param input
     *            The initial object, which is to be cloned and used in
     *            operations.
     * @param context
     *            The context object, which is to be parsed and used in
     *            operations.
     * @throws JsonLdError
     *             If there was an error cloning the object, or in parsing the
     *             context.
     */
    private void initialize(Object input, Object context) throws JsonLdError {
        if (input instanceof List || input instanceof Map) {
            this.value = JsonLdUtils.clone(input);
        }
        this.context = new Context(opts);
        if (context != null) {
            this.context = this.context.parse(context);
        }
    }

    /***
     * ____ _ _ _ _ _ _ / ___|___ _ __ ___ _ __ __ _ ___| |_ / \ | | __ _ ___ _
     * __(_) |_| |__ _ __ ___ | | / _ \| '_ ` _ \| '_ \ / _` |/ __| __| / _ \ |
     * |/ _` |/ _ \| '__| | __| '_ \| '_ ` _ \ | |__| (_) | | | | | | |_) | (_|
     * | (__| |_ / ___ \| | (_| | (_) | | | | |_| | | | | | | | | \____\___/|_|
     * |_| |_| .__/ \__,_|\___|\__| /_/ \_\_|\__, |\___/|_| |_|\__|_| |_|_| |_|
     * |_| |_| |___/
     */
    /**
     * Compaction Algorithm
     *
     * http://json-ld.org/spec/latest/json-ld-api/#compaction-algorithm
     *
     * @param activeCtx
     *            The Active Context
     * @param activeProperty
     *            The Active Property
     * @param element
     *            The current element
     * @param compactArrays
     *            True to compact arrays.
     * @return The compacted JSON-LD object.
     * @throws JsonLdError
     *             If there was an error during compaction.
     */
    public Object compact(Context activeCtx, String activeProperty, Object element, boolean compactArrays) throws JsonLdError {
        if (element instanceof List) {
            final List<Object> result = new ArrayList<Object>();
            for (final Object item : (List<Object>) element) {
                final Object compactedItem = compact(activeCtx, activeProperty, item, compactArrays);
                if (compactedItem != null) {
                    result.add(compactedItem);
                }
            }
            if (compactArrays && result.size() == 1 && activeCtx.getContainer(activeProperty) == null) {
                return result.get(0);
            }
            return result;
        }
        if (element instanceof Map) {
            final Map<String, Object> elem = (Map<String, Object>) element;
            if (elem.containsKey(JsonLdConsts.VALUE) || elem.containsKey(JsonLdConsts.ID)) {
                final Object compactedValue = activeCtx.compactValue(activeProperty, elem);
                if (!(compactedValue instanceof Map || compactedValue instanceof List)) {
                    return compactedValue;
                }
            }
            final boolean insideReverse = (JsonLdConsts.REVERSE.equals(activeProperty));
            final Map<String, Object> result = newMap();
            final List<String> keys = new ArrayList<String>(elem.keySet());
            Collections.sort(keys);
            for (final String expandedProperty : keys) {
                final Object expandedValue = elem.get(expandedProperty);
                if (JsonLdConsts.ID.equals(expandedProperty) || JsonLdConsts.TYPE.equals(expandedProperty)) {
                    Object compactedValue;
                    if (expandedValue instanceof String) {
                        compactedValue = activeCtx.compactIri((String) expandedValue, JsonLdConsts.TYPE.equals(expandedProperty));
                    } else {
                        final List<String> types = new ArrayList<String>();
                        for (final String expandedType : (List<String>) expandedValue) {
                            types.add(activeCtx.compactIri(expandedType, true));
                        }
                        if (types.size() == 1) {
                            compactedValue = types.get(0);
                        } else {
                            compactedValue = types;
                        }
                    }
                    final String alias = activeCtx.compactIri(expandedProperty, true);
                    result.put(alias, compactedValue);
                    continue;
                }
                if (JsonLdConsts.REVERSE.equals(expandedProperty)) {
                    final Map<String, Object> compactedValue = (Map<String, Object>) compact(activeCtx, JsonLdConsts.REVERSE, expandedValue, compactArrays);
                    for (final String property : new HashSet<String>(compactedValue.keySet())) {
                        final Object value = compactedValue.get(property);
                        if (activeCtx.isReverseProperty(property)) {
                            if ((JsonLdConsts.SET.equals(activeCtx.getContainer(property)) || !compactArrays) && !(value instanceof List)) {
                                final List<Object> tmp = new ArrayList<Object>();
                                tmp.add(value);
                                result.put(property, tmp);
                            }
                            if (!result.containsKey(property)) {
                                result.put(property, value);
                            } else {
                                if (!(result.get(property) instanceof List)) {
                                    final List<Object> tmp = new ArrayList<Object>();
                                    tmp.add(result.put(property, tmp));
                                }
                                if (value instanceof List) {
                                    ((List<Object>) result.get(property)).addAll((List<Object>) value);
                                } else {
                                    ((List<Object>) result.get(property)).add(value);
                                }
                            }
                            compactedValue.remove(property);
                        }
                    }
                    if (!compactedValue.isEmpty()) {
                        final String alias = activeCtx.compactIri(JsonLdConsts.REVERSE, true);
                        result.put(alias, compactedValue);
                    }
                    continue;
                }
                if (JsonLdConsts.INDEX.equals(expandedProperty) && JsonLdConsts.INDEX.equals(activeCtx.getContainer(activeProperty))) {
                    continue;
                } else {
                    if (JsonLdConsts.INDEX.equals(expandedProperty) || JsonLdConsts.VALUE.equals(expandedProperty) || JsonLdConsts.LANGUAGE.equals(expandedProperty)) {
                        final String alias = activeCtx.compactIri(expandedProperty, true);
                        result.put(alias, expandedValue);
                        continue;
                    }
                }
                if (((List<Object>) expandedValue).size() == 0) {
                    final String itemActiveProperty = activeCtx.compactIri(expandedProperty, expandedValue, true, insideReverse);
                    if (!result.containsKey(itemActiveProperty)) {
                        result.put(itemActiveProperty, new ArrayList<Object>());
                    } else {
                        final Object value = result.get(itemActiveProperty);
                        if (!(value instanceof List)) {
                            final List<Object> tmp = new ArrayList<Object>();
                            tmp.add(value);
                            result.put(itemActiveProperty, tmp);
                        }
                    }
                }
                for (final Object expandedItem : (List<Object>) expandedValue) {
                    final String itemActiveProperty = activeCtx.compactIri(expandedProperty, expandedItem, true, insideReverse);
                    final String container = activeCtx.getContainer(itemActiveProperty);
                    final boolean isList = (expandedItem instanceof Map && ((Map<String, Object>) expandedItem).containsKey(JsonLdConsts.LIST));
                    Object list = null;
                    if (isList) {
                        list = ((Map<String, Object>) expandedItem).get(JsonLdConsts.LIST);
                    }
                    Object compactedItem = compact(activeCtx, itemActiveProperty, isList ? list : expandedItem, compactArrays);
                    if (isList) {
                        if (!(compactedItem instanceof List)) {
                            final List<Object> tmp = new ArrayList<Object>();
                            tmp.add(compactedItem);
                            compactedItem = tmp;
                        }
                        if (!JsonLdConsts.LIST.equals(container)) {
                            final Map<String, Object> wrapper = newMap();
                            wrapper.put(activeCtx.compactIri(JsonLdConsts.LIST, true), compactedItem);
                            compactedItem = wrapper;
                            if (((Map<String, Object>) expandedItem).containsKey(JsonLdConsts.INDEX)) {
                                ((Map<String, Object>) compactedItem).put(activeCtx.compactIri(JsonLdConsts.INDEX, true), ((Map<String, Object>) expandedItem).get(JsonLdConsts.INDEX));
                            }
                        } else {
                            if (result.containsKey(itemActiveProperty)) {
                                throw new JsonLdError(Error.COMPACTION_TO_LIST_OF_LISTS, "There cannot be two list objects associated with an active property that has a container mapping");
                            }
                        }
                    }
                    if (JsonLdConsts.LANGUAGE.equals(container) || JsonLdConsts.INDEX.equals(container)) {
                        Map<String, Object> mapObject;
                        if (result.containsKey(itemActiveProperty)) {
                            mapObject = (Map<String, Object>) result.get(itemActiveProperty);
                        } else {
                            mapObject = newMap();
                            result.put(itemActiveProperty, mapObject);
                        }
                        if (JsonLdConsts.LANGUAGE.equals(container) && (compactedItem instanceof Map && ((Map<String, Object>) compactedItem).containsKey(JsonLdConsts.VALUE))) {
                            compactedItem = ((Map<String, Object>) compactedItem).get(JsonLdConsts.VALUE);
                        }
                        final String mapKey = (String) ((Map<String, Object>) expandedItem).get(container);
                        if (!mapObject.containsKey(mapKey)) {
                            mapObject.put(mapKey, compactedItem);
                        } else {
                            List<Object> tmp;
                            if (!(mapObject.get(mapKey) instanceof List)) {
                                tmp = new ArrayList<Object>();
                                tmp.add(mapObject.put(mapKey, tmp));
                            } else {
                                tmp = (List<Object>) mapObject.get(mapKey);
                            }
                            tmp.add(compactedItem);
                        }
                    } else {
                        final Boolean check = (!compactArrays || JsonLdConsts.SET.equals(container) || JsonLdConsts.LIST.equals(container) || JsonLdConsts.LIST.equals(expandedProperty) || JsonLdConsts.GRAPH.equals(expandedProperty)) && (!(compactedItem instanceof List));
                        if (check) {
                            final List<Object> tmp = new ArrayList<Object>();
                            tmp.add(compactedItem);
                            compactedItem = tmp;
                        }
                        if (!result.containsKey(itemActiveProperty)) {
                            result.put(itemActiveProperty, compactedItem);
                        } else {
                            if (!(result.get(itemActiveProperty) instanceof List)) {
                                final List<Object> tmp = new ArrayList<Object>();
                                tmp.add(result.put(itemActiveProperty, tmp));
                            }
                            if (compactedItem instanceof List) {
                                ((List<Object>) result.get(itemActiveProperty)).addAll((List<Object>) compactedItem);
                            } else {
                                ((List<Object>) result.get(itemActiveProperty)).add(compactedItem);
                            }
                        }
                    }
                }
            }
            return result;
        }
        return element;
    }

    /**
     * Compaction Algorithm
     *
     * http://json-ld.org/spec/latest/json-ld-api/#compaction-algorithm
     *
     * @param activeCtx
     *            The Active Context
     * @param activeProperty
     *            The Active Property
     * @param element
     *            The current element
     * @return The compacted JSON-LD object.
     * @throws JsonLdError
     *             If there was an error during compaction.
     */
    public Object compact(Context activeCtx, String activeProperty, Object element) throws JsonLdError {
        return compact(activeCtx, activeProperty, element, true);
    }

    /***
     * _____ _ _ _ _ _ _ | ____|_ ___ __ __ _ _ __ __| | / \ | | __ _ ___ _
     * __(_) |_| |__ _ __ ___ | _| \ \/ / '_ \ / _` | '_ \ / _` | / _ \ | |/ _`
     * |/ _ \| '__| | __| '_ \| '_ ` _ \ | |___ > <| |_) | (_| | | | | (_| | /
     * ___ \| | (_| | (_) | | | | |_| | | | | | | | | |_____/_/\_\ .__/ \__,_|_|
     * |_|\__,_| /_/ \_\_|\__, |\___/|_| |_|\__|_| |_|_| |_| |_| |_| |___/
     */
    /**
     * Expansion Algorithm
     *
     * http://json-ld.org/spec/latest/json-ld-api/#expansion-algorithm
     *
     * @param activeCtx
     *            The Active Context
     * @param activeProperty
     *            The Active Property
     * @param element
     *            The current element
     * @return The expanded JSON-LD object.
     * @throws JsonLdError
     *             If there was an error during expansion.
     */
    public Object expand(Context activeCtx, String activeProperty, Object element) throws JsonLdError {
        if (element == null) {
            return null;
        }
        if (element instanceof List) {
            final List<Object> result = new ArrayList<Object>();
            for (final Object item : (List<Object>) element) {
                final Object v = expand(activeCtx, activeProperty, item);
                if ((JsonLdConsts.LIST.equals(activeProperty) || JsonLdConsts.LIST.equals(activeCtx.getContainer(activeProperty))) && (v instanceof List || (v instanceof Map && ((Map<String, Object>) v).containsKey(JsonLdConsts.LIST)))) {
                    throw new JsonLdError(Error.LIST_OF_LISTS, "lists of lists are not permitted.");
                } else {
                    if (v != null) {
                        if (v instanceof List) {
                            result.addAll((Collection<? extends Object>) v);
                        } else {
                            result.add(v);
                        }
                    }
                }
            }
            return result;
        } else {
            if (element instanceof Map) {
                final Map<String, Object> elem = (Map<String, Object>) element;
                if (elem.containsKey(JsonLdConsts.CONTEXT)) {
                    activeCtx = activeCtx.parse(elem.get(JsonLdConsts.CONTEXT));
                }
                Map<String, Object> result = newMap();
                final List<String> keys = new ArrayList<String>(elem.keySet());
                Collections.sort(keys);
                for (final String key : keys) {
                    final Object value = elem.get(key);
                    if (key.equals(JsonLdConsts.CONTEXT)) {
                        continue;
                    }
                    final String expandedProperty = activeCtx.expandIri(key, false, true, null, null);
                    Object expandedValue = null;
                    if (expandedProperty == null || (!expandedProperty.contains(":") && !isKeyword(expandedProperty))) {
                        continue;
                    }
                    if (isKeyword(expandedProperty)) {
                        if (JsonLdConsts.REVERSE.equals(activeProperty)) {
                            throw new JsonLdError(Error.INVALID_REVERSE_PROPERTY_MAP, "a keyword cannot be used as a @reverse propery");
                        }
                        if (result.containsKey(expandedProperty)) {
                            throw new JsonLdError(Error.COLLIDING_KEYWORDS, expandedProperty + " already exists in result");
                        }
                        if (JsonLdConsts.ID.equals(expandedProperty)) {
                            if (!(value instanceof String)) {
                                throw new JsonLdError(Error.INVALID_ID_VALUE, "value of @id must be a string");
                            }
                            expandedValue = activeCtx.expandIri((String) value, true, false, null, null);
                        } else {
                            if (JsonLdConsts.TYPE.equals(expandedProperty)) {
                                if (value instanceof List) {
                                    expandedValue = new ArrayList<String>();
                                    for (final Object v : (List) value) {
                                        if (!(v instanceof String)) {
                                            throw new JsonLdError(Error.INVALID_TYPE_VALUE, "@type value must be a string or array of strings");
                                        }
                                        ((List<String>) expandedValue).add(activeCtx.expandIri((String) v, true, true, null, null));
                                    }
                                } else {
                                    if (value instanceof String) {
                                        expandedValue = activeCtx.expandIri((String) value, true, true, null, null);
                                    } else {
                                        if (value instanceof Map) {
                                            if (((Map<String, Object>) value).size() != 0) {
                                                throw new JsonLdError(Error.INVALID_TYPE_VALUE, "@type value must be a an empty object for framing");
                                            }
                                            expandedValue = value;
                                        } else {
                                            throw new JsonLdError(Error.INVALID_TYPE_VALUE, "@type value must be a string or array of strings");
                                        }
                                    }
                                }
                            } else {
                                if (JsonLdConsts.GRAPH.equals(expandedProperty)) {
                                    expandedValue = expand(activeCtx, JsonLdConsts.GRAPH, value);
                                } else {
                                    if (JsonLdConsts.VALUE.equals(expandedProperty)) {
                                        if (value != null && (value instanceof Map || value instanceof List)) {
                                            throw new JsonLdError(Error.INVALID_VALUE_OBJECT_VALUE, "value of " + expandedProperty + " must be a scalar or null");
                                        }
                                        expandedValue = value;
                                        if (expandedValue == null) {
                                            result.put(JsonLdConsts.VALUE, null);
                                            continue;
                                        }
                                    } else {
                                        if (JsonLdConsts.LANGUAGE.equals(expandedProperty)) {
                                            if (!(value instanceof String)) {
                                                throw new JsonLdError(Error.INVALID_LANGUAGE_TAGGED_STRING, "Value of " + expandedProperty + " must be a string");
                                            }
                                            expandedValue = ((String) value).toLowerCase();
                                        } else {
                                            if (JsonLdConsts.INDEX.equals(expandedProperty)) {
                                                if (!(value instanceof String)) {
                                                    throw new JsonLdError(Error.INVALID_INDEX_VALUE, "Value of " + expandedProperty + " must be a string");
                                                }
                                                expandedValue = value;
                                            } else {
                                                if (JsonLdConsts.LIST.equals(expandedProperty)) {
                                                    if (activeProperty == null || JsonLdConsts.GRAPH.equals(activeProperty)) {
                                                        continue;
                                                    }
                                                    expandedValue = expand(activeCtx, activeProperty, value);
                                                    if (!(expandedValue instanceof List)) {
                                                        final List<Object> tmp = new ArrayList<Object>();
                                                        tmp.add(expandedValue);
                                                        expandedValue = tmp;
                                                    }
                                                    for (final Object o : (List<Object>) expandedValue) {
                                                        if (o instanceof Map && ((Map<String, Object>) o).containsKey(JsonLdConsts.LIST)) {
                                                            throw new JsonLdError(Error.LIST_OF_LISTS, "A list may not contain another list");
                                                        }
                                                    }
                                                } else {
                                                    if (JsonLdConsts.SET.equals(expandedProperty)) {
                                                        expandedValue = expand(activeCtx, activeProperty, value);
                                                    } else {
                                                        if (JsonLdConsts.REVERSE.equals(expandedProperty)) {
                                                            if (!(value instanceof Map)) {
                                                                throw new JsonLdError(Error.INVALID_REVERSE_VALUE, "@reverse value must be an object");
                                                            }
                                                            expandedValue = expand(activeCtx, JsonLdConsts.REVERSE, value);
                                                            if (((Map<String, Object>) expandedValue).containsKey(JsonLdConsts.REVERSE)) {
                                                                final Map<String, Object> reverse = (Map<String, Object>) ((Map<String, Object>) expandedValue).get(JsonLdConsts.REVERSE);
                                                                for (final String property : reverse.keySet()) {
                                                                    final Object item = reverse.get(property);
                                                                    if (!result.containsKey(property)) {
                                                                        result.put(property, new ArrayList<Object>());
                                                                    }
                                                                    if (item instanceof List) {
                                                                        ((List<Object>) result.get(property)).addAll((List<Object>) item);
                                                                    } else {
                                                                        ((List<Object>) result.get(property)).add(item);
                                                                    }
                                                                }
                                                            }
                                                            if (((Map<String, Object>) expandedValue).size() > (((Map<String, Object>) expandedValue).containsKey(JsonLdConsts.REVERSE) ? 1 : 0)) {
                                                                if (!result.containsKey(JsonLdConsts.REVERSE)) {
                                                                    result.put(JsonLdConsts.REVERSE, newMap());
                                                                }
                                                                final Map<String, Object> reverseMap = (Map<String, Object>) result.get(JsonLdConsts.REVERSE);
                                                                for (final String property : ((Map<String, Object>) expandedValue).keySet()) {
                                                                    if (JsonLdConsts.REVERSE.equals(property)) {
                                                                        continue;
                                                                    }
                                                                    final List<Object> items = (List<Object>) ((Map<String, Object>) expandedValue).get(property);
                                                                    for (final Object item : items) {
                                                                        if (item instanceof Map && (((Map<String, Object>) item).containsKey(JsonLdConsts.VALUE) || ((Map<String, Object>) item).containsKey(JsonLdConsts.LIST))) {
                                                                            throw new JsonLdError(Error.INVALID_REVERSE_PROPERTY_VALUE);
                                                                        }
                                                                        if (!reverseMap.containsKey(property)) {
                                                                            reverseMap.put(property, new ArrayList<Object>());
                                                                        }
                                                                        ((List<Object>) reverseMap.get(property)).add(item);
                                                                    }
                                                                }
                                                            }
                                                            continue;
                                                        } else {
                                                            if (JsonLdConsts.EXPLICIT.equals(expandedProperty) || JsonLdConsts.DEFAULT.equals(expandedProperty) || JsonLdConsts.EMBED.equals(expandedProperty) || JsonLdConsts.EMBED_CHILDREN.equals(expandedProperty) || JsonLdConsts.OMIT_DEFAULT.equals(expandedProperty)) {
                                                                expandedValue = expand(activeCtx, expandedProperty, value);
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (expandedValue != null) {
                            result.put(expandedProperty, expandedValue);
                        }
                        continue;
                    } else {
                        if (JsonLdConsts.LANGUAGE.equals(activeCtx.getContainer(key)) && value instanceof Map) {
                            expandedValue = new ArrayList<Object>();
                            for (final String language : ((Map<String, Object>) value).keySet()) {
                                Object languageValue = ((Map<String, Object>) value).get(language);
                                if (!(languageValue instanceof List)) {
                                    final Object tmp = languageValue;
                                    languageValue = new ArrayList<Object>();
                                    ((List<Object>) languageValue).add(tmp);
                                }
                                for (final Object item : (List<Object>) languageValue) {
                                    if (!(item instanceof String)) {
                                        throw new JsonLdError(Error.INVALID_LANGUAGE_MAP_VALUE, "Expected " + item.toString() + " to be a string");
                                    }
                                    final Map<String, Object> tmp = newMap();
                                    tmp.put(JsonLdConsts.VALUE, item);
                                    tmp.put(JsonLdConsts.LANGUAGE, language.toLowerCase());
                                    ((List<Object>) expandedValue).add(tmp);
                                }
                            }
                        } else {
                            if (JsonLdConsts.INDEX.equals(activeCtx.getContainer(key)) && value instanceof Map) {
                                expandedValue = new ArrayList<Object>();
                                final List<String> indexKeys = new ArrayList<String>(((Map<String, Object>) value).keySet());
                                Collections.sort(indexKeys);
                                for (final String index : indexKeys) {
                                    Object indexValue = ((Map<String, Object>) value).get(index);
                                    if (!(indexValue instanceof List)) {
                                        final Object tmp = indexValue;
                                        indexValue = new ArrayList<Object>();
                                        ((List<Object>) indexValue).add(tmp);
                                    }
                                    indexValue = expand(activeCtx, key, indexValue);
                                    for (final Map<String, Object> item : (List<Map<String, Object>>) indexValue) {
                                        if (!item.containsKey(JsonLdConsts.INDEX)) {
                                            item.put(JsonLdConsts.INDEX, index);
                                        }
                                        ((List<Object>) expandedValue).add(item);
                                    }
                                }
                            } else {
                                expandedValue = expand(activeCtx, key, value);
                            }
                        }
                    }
                    if (expandedValue == null) {
                        continue;
                    }
                    if (JsonLdConsts.LIST.equals(activeCtx.getContainer(key))) {
                        if (!(expandedValue instanceof Map) || !((Map<String, Object>) expandedValue).containsKey(JsonLdConsts.LIST)) {
                            Object tmp = expandedValue;
                            if (!(tmp instanceof List)) {
                                tmp = new ArrayList<Object>();
                                ((List<Object>) tmp).add(expandedValue);
                            }
                            expandedValue = newMap();
                            ((Map<String, Object>) expandedValue).put(JsonLdConsts.LIST, tmp);
                        }
                    }
                    if (activeCtx.isReverseProperty(key)) {
                        if (!result.containsKey(JsonLdConsts.REVERSE)) {
                            result.put(JsonLdConsts.REVERSE, newMap());
                        }
                        final Map<String, Object> reverseMap = (Map<String, Object>) result.get(JsonLdConsts.REVERSE);
                        if (!(expandedValue instanceof List)) {
                            final Object tmp = expandedValue;
                            expandedValue = new ArrayList<Object>();
                            ((List<Object>) expandedValue).add(tmp);
                        }
                        for (final Object item : (List<Object>) expandedValue) {
                            if (item instanceof Map && (((Map<String, Object>) item).containsKey(JsonLdConsts.VALUE) || ((Map<String, Object>) item).containsKey(JsonLdConsts.LIST))) {
                                throw new JsonLdError(Error.INVALID_REVERSE_PROPERTY_VALUE);
                            }
                            if (!reverseMap.containsKey(expandedProperty)) {
                                reverseMap.put(expandedProperty, new ArrayList<Object>());
                            }
                            if (item instanceof List) {
                                ((List<Object>) reverseMap.get(expandedProperty)).addAll((List<Object>) item);
                            } else {
                                ((List<Object>) reverseMap.get(expandedProperty)).add(item);
                            }
                        }
                    } else {
                        if (!result.containsKey(expandedProperty)) {
                            result.put(expandedProperty, new ArrayList<Object>());
                        }
                        if (expandedValue instanceof List) {
                            ((List<Object>) result.get(expandedProperty)).addAll((List<Object>) expandedValue);
                        } else {
                            ((List<Object>) result.get(expandedProperty)).add(expandedValue);
                        }
                    }
                }
                if (result.containsKey(JsonLdConsts.VALUE)) {
                    final Set<String> keySet = new HashSet(result.keySet());
                    keySet.remove(JsonLdConsts.VALUE);
                    keySet.remove(JsonLdConsts.INDEX);
                    final boolean langremoved = keySet.remove(JsonLdConsts.LANGUAGE);
                    final boolean typeremoved = keySet.remove(JsonLdConsts.TYPE);
                    if ((langremoved && typeremoved) || !keySet.isEmpty()) {
                        throw new JsonLdError(Error.INVALID_VALUE_OBJECT, "value object has unknown keys");
                    }
                    final Object rval = result.get(JsonLdConsts.VALUE);
                    if (rval == null) {
                        return null;
                    }
                    if (!(rval instanceof String) && result.containsKey(JsonLdConsts.LANGUAGE)) {
                        throw new JsonLdError(Error.INVALID_LANGUAGE_TAGGED_VALUE, "when @language is used, @value must be a string");
                    } else {
                        if (result.containsKey(JsonLdConsts.TYPE)) {
                            if (!(result.get(JsonLdConsts.TYPE) instanceof String) || ((String) result.get(JsonLdConsts.TYPE)).startsWith("_:") || !((String) result.get(JsonLdConsts.TYPE)).contains(":")) {
                                throw new JsonLdError(Error.INVALID_TYPED_VALUE, "value of @type must be an IRI");
                            }
                        }
                    }
                } else {
                    if (result.containsKey(JsonLdConsts.TYPE)) {
                        final Object rtype = result.get(JsonLdConsts.TYPE);
                        if (!(rtype instanceof List)) {
                            final List<Object> tmp = new ArrayList<Object>();
                            tmp.add(rtype);
                            result.put(JsonLdConsts.TYPE, tmp);
                        }
                    } else {
                        if (result.containsKey(JsonLdConsts.SET) || result.containsKey(JsonLdConsts.LIST)) {
                            if (result.size() > (result.containsKey(JsonLdConsts.INDEX) ? 2 : 1)) {
                                throw new JsonLdError(Error.INVALID_SET_OR_LIST_OBJECT, "@set or @list may only contain @index");
                            }
                            if (result.containsKey(JsonLdConsts.SET)) {
                                return result.get(JsonLdConsts.SET);
                            }
                        }
                    }
                }
                if (result.containsKey(JsonLdConsts.LANGUAGE) && result.size() == 1) {
                    result = null;
                }
                if (activeProperty == null || JsonLdConsts.GRAPH.equals(activeProperty)) {
                    if (result != null && (result.size() == 0 || result.containsKey(JsonLdConsts.VALUE) || result.containsKey(JsonLdConsts.LIST))) {
                        result = null;
                    } else {
                        if (result != null && result.containsKey(JsonLdConsts.ID) && result.size() == 1) {
                            result = null;
                        }
                    }
                }
                return result;
            } else {
                if (activeProperty == null || JsonLdConsts.GRAPH.equals(activeProperty)) {
                    return null;
                }
                return activeCtx.expandValue(activeProperty, element);
            }
        }
    }

    /**
     * Expansion Algorithm
     *
     * http://json-ld.org/spec/latest/json-ld-api/#expansion-algorithm
     *
     * @param activeCtx
     *            The Active Context
     * @param element
     *            The current element
     * @return The expanded JSON-LD object.
     * @throws JsonLdError
     *             If there was an error during expansion.
     */
    public Object expand(Context activeCtx, Object element) throws JsonLdError {
        return expand(activeCtx, null, element);
    }

    /***
     * _____ _ _ _ _ _ _ _ _ | ___| | __ _| |_| |_ ___ _ __ / \ | | __ _ ___ _
     * __(_) |_| |__ _ __ ___ | |_ | |/ _` | __| __/ _ \ '_ \ / _ \ | |/ _` |/ _
     * \| '__| | __| '_ \| '_ ` _ \ | _| | | (_| | |_| || __/ | | | / ___ \| |
     * (_| | (_) | | | | |_| | | | | | | | | |_| |_|\__,_|\__|\__\___|_| |_| /_/
     * \_\_|\__, |\___/|_| |_|\__|_| |_|_| |_| |_| |___/
     */
    void generateNodeMap(Object element, Map<String, Object> nodeMap) throws JsonLdError {
        generateNodeMap(element, nodeMap, JsonLdConsts.DEFAULT, null, null, null);
    }

    void generateNodeMap(Object element, Map<String, Object> nodeMap, String activeGraph) throws JsonLdError {
        generateNodeMap(element, nodeMap, activeGraph, null, null, null);
    }

    void generateNodeMap(Object element, Map<String, Object> nodeMap, String activeGraph, Object activeSubject, String activeProperty, Map<String, Object> list) throws JsonLdError {
        if (element instanceof List) {
            for (final Object item : (List<Object>) element) {
                generateNodeMap(item, nodeMap, activeGraph, activeSubject, activeProperty, list);
            }
            return;
        }
        final Map<String, Object> elem = (Map<String, Object>) element;
        if (!nodeMap.containsKey(activeGraph)) {
            nodeMap.put(activeGraph, newMap());
        }
        final Map<String, Object> graph = (Map<String, Object>) nodeMap.get(activeGraph);
        Map<String, Object> node = (Map<String, Object>) (activeSubject == null ? null : graph.get(activeSubject));
        if (elem.containsKey(JsonLdConsts.TYPE)) {
            List<String> oldTypes;
            final List<String> newTypes = new ArrayList<String>();
            if (elem.get(JsonLdConsts.TYPE) instanceof List) {
                oldTypes = (List<String>) elem.get(JsonLdConsts.TYPE);
            } else {
                oldTypes = new ArrayList<String>(4);
                oldTypes.add((String) elem.get(JsonLdConsts.TYPE));
            }
            for (final String item : oldTypes) {
                if (item.startsWith("_:")) {
                    newTypes.add(generateBlankNodeIdentifier(item));
                } else {
                    newTypes.add(item);
                }
            }
            if (elem.get(JsonLdConsts.TYPE) instanceof List) {
                elem.put(JsonLdConsts.TYPE, newTypes);
            } else {
                elem.put(JsonLdConsts.TYPE, newTypes.get(0));
            }
        }
        if (elem.containsKey(JsonLdConsts.VALUE)) {
            if (list == null) {
                JsonLdUtils.mergeValue(node, activeProperty, elem);
            } else {
                JsonLdUtils.mergeValue(list, JsonLdConsts.LIST, elem);
            }
        } else {
            if (elem.containsKey(JsonLdConsts.LIST)) {
                final Map<String, Object> result = newMap(JsonLdConsts.LIST, new ArrayList<Object>(4));
                generateNodeMap(elem.get(JsonLdConsts.LIST), nodeMap, activeGraph, activeSubject, activeProperty, result);
                JsonLdUtils.mergeValue(node, activeProperty, result);
            } else {
                String id = (String) elem.remove(JsonLdConsts.ID);
                if (id != null) {
                    if (id.startsWith("_:")) {
                        id = generateBlankNodeIdentifier(id);
                    }
                } else {
                    id = generateBlankNodeIdentifier(null);
                }
                if (!graph.containsKey(id)) {
                    final Map<String, Object> tmp = newMap(JsonLdConsts.ID, id);
                    graph.put(id, tmp);
                }
                if (activeSubject instanceof Map) {
                    JsonLdUtils.mergeValue((Map<String, Object>) graph.get(id), activeProperty, activeSubject);
                } else {
                    if (activeProperty != null) {
                        final Map<String, Object> reference = newMap(JsonLdConsts.ID, id);
                        if (list == null) {
                            JsonLdUtils.mergeValue(node, activeProperty, reference);
                        } else {
                            JsonLdUtils.mergeValue(list, JsonLdConsts.LIST, reference);
                        }
                    }
                }
                node = (Map<String, Object>) graph.get(id);
                if (elem.containsKey(JsonLdConsts.TYPE)) {
                    for (final Object type : (List<Object>) elem.remove(JsonLdConsts.TYPE)) {
                        JsonLdUtils.mergeValue(node, JsonLdConsts.TYPE, type);
                    }
                }
                if (elem.containsKey(JsonLdConsts.INDEX)) {
                    final Object elemIndex = elem.remove(JsonLdConsts.INDEX);
                    if (node.containsKey(JsonLdConsts.INDEX)) {
                        if (!JsonLdUtils.deepCompare(node.get(JsonLdConsts.INDEX), elemIndex)) {
                            throw new JsonLdError(Error.CONFLICTING_INDEXES);
                        }
                    } else {
                        node.put(JsonLdConsts.INDEX, elemIndex);
                    }
                }
                if (elem.containsKey(JsonLdConsts.REVERSE)) {
                    final Map<String, Object> referencedNode = newMap(JsonLdConsts.ID, id);
                    final Map<String, Object> reverseMap = (Map<String, Object>) elem.remove(JsonLdConsts.REVERSE);
                    for (final String property : reverseMap.keySet()) {
                        final List<Object> values = (List<Object>) reverseMap.get(property);
                        for (final Object value : values) {
                            generateNodeMap(value, nodeMap, activeGraph, referencedNode, property, null);
                        }
                    }
                }
                if (elem.containsKey(JsonLdConsts.GRAPH)) {
                    generateNodeMap(elem.remove(JsonLdConsts.GRAPH), nodeMap, id, null, null, null);
                }
                final List<String> keys = new ArrayList<String>(elem.keySet());
                Collections.sort(keys);
                for (String property : keys) {
                    final Object value = elem.get(property);
                    if (property.startsWith("_:")) {
                        property = generateBlankNodeIdentifier(property);
                    }
                    if (!node.containsKey(property)) {
                        node.put(property, new ArrayList<Object>(4));
                    }
                    generateNodeMap(value, nodeMap, activeGraph, id, property, null);
                }
            }
        }
    }

    /**
     * Blank Node identifier map specified in:
     *
     * http://www.w3.org/TR/json-ld-api/#generate-blank-node-identifier
     */
    private final Map<String, String> blankNodeIdentifierMap = new LinkedHashMap<String, String>();

    /**
     * Counter specified in:
     *
     * http://www.w3.org/TR/json-ld-api/#generate-blank-node-identifier
     */
    private int blankNodeCounter = 0;

    /**
     * Generates a blank node identifier for the given key using the algorithm
     * specified in:
     *
     * http://www.w3.org/TR/json-ld-api/#generate-blank-node-identifier
     *
     * @param id
     *            The id, or null to generate a fresh, unused, blank node
     *            identifier.
     * @return A blank node identifier based on id if it was not null, or a
     *         fresh, unused, blank node identifier if it was null.
     */
    String generateBlankNodeIdentifier(String id) {
        if (id != null && blankNodeIdentifierMap.containsKey(id)) {
            return blankNodeIdentifierMap.get(id);
        }
        final String bnid = "_:b" + blankNodeCounter++;
        if (id != null) {
            blankNodeIdentifierMap.put(id, bnid);
        }
        return bnid;
    }

    /**
     * Generates a fresh, unused, blank node identifier using the algorithm
     * specified in:
     *
     * http://www.w3.org/TR/json-ld-api/#generate-blank-node-identifier
     *
     * @return A fresh, unused, blank node identifier.
     */
    String generateBlankNodeIdentifier() {
        return generateBlankNodeIdentifier(null);
    }

    private class FramingContext {

        public boolean embed;

        public boolean explicit;

        public boolean omitDefault;

        public FramingContext() {
            embed = true;
            explicit = false;
            omitDefault = false;
            embeds = null;
        }

        public FramingContext(JsonLdOptions opts) {
            this();
            if (opts.getEmbed() != null) {
                this.embed = opts.getEmbed();
            }
            if (opts.getExplicit() != null) {
                this.explicit = opts.getExplicit();
            }
            if (opts.getOmitDefault() != null) {
                this.omitDefault = opts.getOmitDefault();
            }
        }

        public Map<String, EmbedNode> embeds = null;
    }

    private class EmbedNode {

        public Object parent = null;

        public String property = null;
    }

    private Map<String, Object> nodeMap;

    /**
     * Performs JSON-LD <a
     * href="http://json-ld.org/spec/latest/json-ld-framing/">framing</a>.
     *
     * @param input
     *            the expanded JSON-LD to frame.
     * @param frame
     *            the expanded JSON-LD frame to use.
     * @return the framed output.
     * @throws JsonLdError
     *             If the framing was not successful.
     */
    public List<Object> frame(Object input, List<Object> frame) throws JsonLdError {
        final FramingContext state = new FramingContext(this.opts);
        final Map<String, Object> nodes = new TreeMap<String, Object>();
        generateNodeMap(input, nodes);
        this.nodeMap = (Map<String, Object>) nodes.get(JsonLdConsts.DEFAULT);
        final List<Object> framed = new ArrayList<Object>();
        frame(state, this.nodeMap, (frame != null && frame.size() > 0 ? (Map<String, Object>) frame.get(0) : newMap()), framed, null);
        return framed;
    }

    /**
     * Frames subjects according to the given frame.
     *
     * @param state
     *            the current framing state.
     * @param subjects
     *            the subjects to filter.
     * @param frame
     *            the frame.
     * @param parent
     *            the parent subject or top-level array.
     * @param property
     *            the parent property, initialized to null.
     * @throws JsonLdError
     *             If there was an error during framing.
     */
    private void frame(FramingContext state, Map<String, Object> nodes, Map<String, Object> frame, Object parent, String property) throws JsonLdError {
        final Map<String, Object> matches = filterNodes(state, nodes, frame);
        Boolean embedOn = getFrameFlag(frame, JsonLdConsts.EMBED, state.embed);
        final Boolean explicicOn = getFrameFlag(frame, JsonLdConsts.EXPLICIT, state.explicit);
        final List<String> ids = new ArrayList<String>(matches.keySet());
        Collections.sort(ids);
        for (final String id : ids) {
            if (property == null) {
                state.embeds = new LinkedHashMap<String, EmbedNode>();
            }
            final Map<String, Object> output = newMap();
            output.put(JsonLdConsts.ID, id);
            final EmbedNode embeddedNode = new EmbedNode();
            embeddedNode.parent = parent;
            embeddedNode.property = property;
            if (embedOn && state.embeds.containsKey(id)) {
                final EmbedNode existing = state.embeds.get(id);
                embedOn = false;
                if (existing.parent instanceof List) {
                    for (final Object p : (List<Object>) existing.parent) {
                        if (JsonLdUtils.compareValues(output, p)) {
                            embedOn = true;
                            break;
                        }
                    }
                } else {
                    if (((Map<String, Object>) existing.parent).containsKey(existing.property)) {
                        for (final Object v : (List<Object>) ((Map<String, Object>) existing.parent).get(existing.property)) {
                            if (v instanceof Map && Obj.equals(id, ((Map<String, Object>) v).get(JsonLdConsts.ID))) {
                                embedOn = true;
                                break;
                            }
                        }
                    }
                }
                if (embedOn) {
                    removeEmbed(state, id);
                }
            }
            if (!embedOn) {
                addFrameOutput(state, parent, property, output);
            } else {
                state.embeds.put(id, embeddedNode);
                final Map<String, Object> element = (Map<String, Object>) matches.get(id);
                List<String> props = new ArrayList<String>(element.keySet());
                Collections.sort(props);
                for (final String prop : props) {
                    if (isKeyword(prop)) {
                        output.put(prop, JsonLdUtils.clone(element.get(prop)));
                        continue;
                    }
                    if (!frame.containsKey(prop)) {
                        if (!explicicOn) {
                            embedValues(state, element, prop, output);
                        }
                        continue;
                    }
                    final List<Object> value = (List<Object>) element.get(prop);
                    for (final Object item : value) {
                        if ((item instanceof Map) && ((Map<String, Object>) item).containsKey(JsonLdConsts.LIST)) {
                            final Map<String, Object> list = newMap();
                            list.put(JsonLdConsts.LIST, new ArrayList<Object>());
                            addFrameOutput(state, output, prop, list);
                            for (final Object listitem : (List<Object>) ((Map<String, Object>) item).get(JsonLdConsts.LIST)) {
                                if (JsonLdUtils.isNodeReference(listitem)) {
                                    final Map<String, Object> tmp = newMap();
                                    final String itemid = (String) ((Map<String, Object>) listitem).get(JsonLdConsts.ID);
                                    tmp.put(itemid, this.nodeMap.get(itemid));
                                    frame(state, tmp, (Map<String, Object>) ((List<Object>) frame.get(prop)).get(0), list, JsonLdConsts.LIST);
                                } else {
                                    addFrameOutput(state, list, JsonLdConsts.LIST, listitem);
                                }
                            }
                        } else {
                            if (JsonLdUtils.isNodeReference(item)) {
                                final Map<String, Object> tmp = newMap();
                                final String itemid = (String) ((Map<String, Object>) item).get(JsonLdConsts.ID);
                                tmp.put(itemid, this.nodeMap.get(itemid));
                                frame(state, tmp, (Map<String, Object>) ((List<Object>) frame.get(prop)).get(0), output, prop);
                            } else {
                                addFrameOutput(state, output, prop, item);
                            }
                        }
                    }
                }
                props = new ArrayList<String>(frame.keySet());
                Collections.sort(props);
                for (final String prop : props) {
                    if (isKeyword(prop)) {
                        continue;
                    }
                    final List<Object> pf = (List<Object>) frame.get(prop);
                    Map<String, Object> propertyFrame = pf.size() > 0 ? (Map<String, Object>) pf.get(0) : null;
                    if (propertyFrame == null) {
                        propertyFrame = newMap();
                    }
                    final boolean omitDefaultOn = getFrameFlag(propertyFrame, JsonLdConsts.OMIT_DEFAULT, state.omitDefault);
                    if (!omitDefaultOn && !output.containsKey(prop)) {
                        Object def = "@null";
                        if (propertyFrame.containsKey(JsonLdConsts.DEFAULT)) {
                            def = JsonLdUtils.clone(propertyFrame.get(JsonLdConsts.DEFAULT));
                        }
                        if (!(def instanceof List)) {
                            final List<Object> tmp = new ArrayList<Object>();
                            tmp.add(def);
                            def = tmp;
                        }
                        final Map<String, Object> tmp1 = newMap(JsonLdConsts.PRESERVE, def);
                        final List<Object> tmp2 = new ArrayList<Object>();
                        tmp2.add(tmp1);
                        output.put(prop, tmp2);
                    }
                }
                addFrameOutput(state, parent, property, output);
            }
        }
    }

    private Boolean getFrameFlag(Map<String, Object> frame, String name, boolean thedefault) {
        Object value = frame.get(name);
        if (value instanceof List) {
            if (((List<Object>) value).size() > 0) {
                value = ((List<Object>) value).get(0);
            }
        }
        if (value instanceof Map && ((Map<String, Object>) value).containsKey(JsonLdConsts.VALUE)) {
            value = ((Map<String, Object>) value).get(JsonLdConsts.VALUE);
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return thedefault;
    }

    /**
     * Removes an existing embed.
     *
     * @param state
     *            the current framing state.
     * @param id
     *            the @id of the embed to remove.
     */
    private static void removeEmbed(FramingContext state, String id) {
        final Map<String, EmbedNode> embeds = state.embeds;
        final EmbedNode embed = embeds.get(id);
        final Object parent = embed.parent;
        final String property = embed.property;
        final Map<String, Object> node = newMap(JsonLdConsts.ID, id);
        if (JsonLdUtils.isNode(parent)) {
            final List<Object> newvals = new ArrayList<Object>();
            final List<Object> oldvals = (List<Object>) ((Map<String, Object>) parent).get(property);
            for (final Object v : oldvals) {
                if (v instanceof Map && Obj.equals(((Map<String, Object>) v).get(JsonLdConsts.ID), id)) {
                    newvals.add(node);
                } else {
                    newvals.add(v);
                }
            }
            ((Map<String, Object>) parent).put(property, newvals);
        }
        removeDependents(embeds, id);
    }

    private static void removeDependents(Map<String, EmbedNode> embeds, String id) {
        for (final String id_dep : new HashSet<String>(embeds.keySet())) {
            final EmbedNode e = embeds.get(id_dep);
            final Object p = e.parent != null ? e.parent : newMap();
            if (!(p instanceof Map)) {
                continue;
            }
            final String pid = (String) ((Map<String, Object>) p).get(JsonLdConsts.ID);
            if (Obj.equals(id, pid)) {
                embeds.remove(id_dep);
                removeDependents(embeds, id_dep);
            }
        }
    }

    private Map<String, Object> filterNodes(FramingContext state, Map<String, Object> nodes, Map<String, Object> frame) throws JsonLdError {
        final Map<String, Object> rval = newMap();
        for (final String id : nodes.keySet()) {
            final Map<String, Object> element = (Map<String, Object>) nodes.get(id);
            if (element != null && filterNode(state, element, frame)) {
                rval.put(id, element);
            }
        }
        return rval;
    }

    private boolean filterNode(FramingContext state, Map<String, Object> node, Map<String, Object> frame) throws JsonLdError {
        final Object types = frame.get(JsonLdConsts.TYPE);
        if (types != null) {
            if (!(types instanceof List)) {
                throw new JsonLdError(Error.SYNTAX_ERROR, "frame @type must be an array");
            }
            Object nodeTypes = node.get(JsonLdConsts.TYPE);
            if (nodeTypes == null) {
                nodeTypes = new ArrayList<Object>();
            } else {
                if (!(nodeTypes instanceof List)) {
                    throw new JsonLdError(Error.SYNTAX_ERROR, "node @type must be an array");
                }
            }
            if (((List<Object>) types).size() == 1 && ((List<Object>) types).get(0) instanceof Map && ((Map<String, Object>) ((List<Object>) types).get(0)).size() == 0) {
                return !((List<Object>) nodeTypes).isEmpty();
            } else {
                for (final Object i : (List<Object>) nodeTypes) {
                    for (final Object j : (List<Object>) types) {
                        if (JsonLdUtils.deepCompare(i, j)) {
                            return true;
                        }
                    }
                }
                return false;
            }
        } else {
            for (final String key : frame.keySet()) {
                if (JsonLdConsts.ID.equals(key) || !isKeyword(key) && !(node.containsKey(key))) {
                    Object frameObject = frame.get(key);
                    if (frameObject instanceof ArrayList) {
                        ArrayList<Object> o = (ArrayList<Object>) frame.get(key);
                        boolean _default = false;
                        for (Object oo : o) {
                            if (oo instanceof Map) {
                                if (((Map) oo).containsKey(JsonLdConsts.DEFAULT)) {
                                    _default = true;
                                }
                            }
                        }
                        if (_default) {
                            continue;
                        }
                    }
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * Adds framing output to the given parent.
     *
     * @param state
     *            the current framing state.
     * @param parent
     *            the parent to add to.
     * @param property
     *            the parent property.
     * @param output
     *            the output to add.
     */
    private static void addFrameOutput(FramingContext state, Object parent, String property, Object output) {
        if (parent instanceof Map) {
            List<Object> prop = (List<Object>) ((Map<String, Object>) parent).get(property);
            if (prop == null) {
                prop = new ArrayList<Object>();
                ((Map<String, Object>) parent).put(property, prop);
            }
            prop.add(output);
        } else {
            ((List) parent).add(output);
        }
    }

    /**
     * Embeds values for the given subject and property into the given output
     * during the framing algorithm.
     *
     * @param state
     *            the current framing state.
     * @param element
     *            the subject.
     * @param property
     *            the property.
     * @param output
     *            the output.
     */
    private void embedValues(FramingContext state, Map<String, Object> element, String property, Object output) {
        final List<Object> objects = (List<Object>) element.get(property);
        for (Object o : objects) {
            if (JsonLdUtils.isNodeReference(o)) {
                final String sid = (String) ((Map<String, Object>) o).get(JsonLdConsts.ID);
                if (!state.embeds.containsKey(sid)) {
                    final EmbedNode embed = new EmbedNode();
                    embed.parent = output;
                    embed.property = property;
                    state.embeds.put(sid, embed);
                    o = newMap();
                    Map<String, Object> s = (Map<String, Object>) this.nodeMap.get(sid);
                    if (s == null) {
                        s = newMap(JsonLdConsts.ID, sid);
                    }
                    for (final String prop : s.keySet()) {
                        if (isKeyword(prop)) {
                            ((Map<String, Object>) o).put(prop, JsonLdUtils.clone(s.get(prop)));
                            continue;
                        }
                        embedValues(state, s, prop, o);
                    }
                }
                addFrameOutput(state, output, property, o);
            } else {
                addFrameOutput(state, output, property, JsonLdUtils.clone(o));
            }
        }
    }

    /**
     * Helper class for node usages
     *
     * @author tristan
     */
    private class UsagesNode {

        public UsagesNode(NodeMapNode node, String property, Map<String, Object> value) {
            this.node = node;
            this.property = property;
            this.value = value;
        }

        public NodeMapNode node = null;

        public String property = null;

        public Map<String, Object> value = null;
    }

    private class NodeMapNode extends LinkedHashMap<String, Object> {

        public List<UsagesNode> usages = new ArrayList(4);

        public NodeMapNode(String id) {
            super();
            this.put(JsonLdConsts.ID, id);
        }

        // helper fucntion for 4.3.3
        public boolean isWellFormedListNode() {
            if (usages.size() != 1) {
                return false;
            }
            int keys = 0;
            if (containsKey(RDF_FIRST)) {
                keys++;
                if (!(get(RDF_FIRST) instanceof List && ((List<Object>) get(RDF_FIRST)).size() == 1)) {
                    return false;
                }
            }
            if (containsKey(RDF_REST)) {
                keys++;
                if (!(get(RDF_REST) instanceof List && ((List<Object>) get(RDF_REST)).size() == 1)) {
                    return false;
                }
            }
            if (containsKey(JsonLdConsts.TYPE)) {
                keys++;
                if (!(get(JsonLdConsts.TYPE) instanceof List && ((List<Object>) get(JsonLdConsts.TYPE)).size() == 1) && RDF_LIST.equals(((List<Object>) get(JsonLdConsts.TYPE)).get(0))) {
                    return false;
                }
            }
            if (containsKey(JsonLdConsts.ID)) {
                keys++;
            }
            if (keys < size()) {
                return false;
            }
            return true;
        }

        // return this node without the usages variable
        public Map<String, Object> serialize() {
            return new LinkedHashMap<String, Object>(this);
        }
    }

    /**
     * Converts RDF statements into JSON-LD.
     *
     * @param dataset
     *            the RDF statements.
     * @return A list of JSON-LD objects found in the given dataset.
     * @throws JsonLdError
     *             If there was an error during conversion from RDF to JSON-LD.
     */
    public List<Object> fromRDF(final RDFDataset dataset) throws JsonLdError {
        final Map<String, NodeMapNode> defaultGraph = new LinkedHashMap<String, NodeMapNode>(4);
        final Map<String, Map<String, NodeMapNode>> graphMap = new LinkedHashMap<String, Map<String, NodeMapNode>>(4);
        graphMap.put(JsonLdConsts.DEFAULT, defaultGraph);
        for (final String name : dataset.graphNames()) {
            final List<RDFDataset.Quad> graph = dataset.getQuads(name);
            Map<String, NodeMapNode> nodeMap;
            if (!graphMap.containsKey(name)) {
                nodeMap = new LinkedHashMap<String, NodeMapNode>();
                graphMap.put(name, nodeMap);
            } else {
                nodeMap = graphMap.get(name);
            }
            if (!JsonLdConsts.DEFAULT.equals(name) && !Obj.contains(defaultGraph, name)) {
                defaultGraph.put(name, new NodeMapNode(name));
            }
            for (final RDFDataset.Quad triple : graph) {
                final String subject = triple.getSubject().getValue();
                final String predicate = triple.getPredicate().getValue();
                final RDFDataset.Node object = triple.getObject();
                NodeMapNode node;
                if (!nodeMap.containsKey(subject)) {
                    node = new NodeMapNode(subject);
                    nodeMap.put(subject, node);
                } else {
                    node = nodeMap.get(subject);
                }
                if ((object.isIRI() || object.isBlankNode()) && !nodeMap.containsKey(object.getValue())) {
                    nodeMap.put(object.getValue(), new NodeMapNode(object.getValue()));
                }
                if (RDF_TYPE.equals(predicate) && (object.isIRI() || object.isBlankNode()) && !opts.getUseRdfType()) {
                    JsonLdUtils.mergeValue(node, JsonLdConsts.TYPE, object.getValue());
                    continue;
                }
                final Map<String, Object> value = object.toObject(opts.getUseNativeTypes());
                JsonLdUtils.mergeValue(node, predicate, value);
                if (object.isBlankNode() || object.isIRI()) {
                    nodeMap.get(object.getValue()).usages.add(new UsagesNode(node, predicate, value));
                }
            }
        }
        for (final String name : graphMap.keySet()) {
            final Map<String, NodeMapNode> graph = graphMap.get(name);
            if (!graph.containsKey(RDF_NIL)) {
                continue;
            }
            final NodeMapNode nil = graph.get(RDF_NIL);
            for (final UsagesNode usage : nil.usages) {
                NodeMapNode node = usage.node;
                String property = usage.property;
                Map<String, Object> head = usage.value;
                final List<Object> list = new ArrayList<Object>(4);
                final List<String> listNodes = new ArrayList<String>(4);
                while (RDF_REST.equals(property) && node.isWellFormedListNode()) {
                    list.add(((List<Object>) node.get(RDF_FIRST)).get(0));
                    listNodes.add((String) node.get(JsonLdConsts.ID));
                    final UsagesNode nodeUsage = node.usages.get(0);
                    node = nodeUsage.node;
                    property = nodeUsage.property;
                    head = nodeUsage.value;
                    if (!JsonLdUtils.isBlankNode(node)) {
                        break;
                    }
                }
                if (RDF_FIRST.equals(property)) {
                    if (RDF_NIL.equals(node.get(JsonLdConsts.ID))) {
                        continue;
                    }
                    final String headId = (String) head.get(JsonLdConsts.ID);
                    head = (Map<String, Object>) ((List<Object>) graph.get(headId).get(RDF_REST)).get(0);
                    list.remove(list.size() - 1);
                    listNodes.remove(listNodes.size() - 1);
                }
                head.remove(JsonLdConsts.ID);
                Collections.reverse(list);
                head.put(JsonLdConsts.LIST, list);
                for (final String nodeId : listNodes) {
                    graph.remove(nodeId);
                }
            }
        }
        final List<Object> result = new ArrayList<Object>(4);
        final List<String> ids = new ArrayList<String>(defaultGraph.keySet());
        Collections.sort(ids);
        for (final String subject : ids) {
            final NodeMapNode node = defaultGraph.get(subject);
            if (graphMap.containsKey(subject)) {
                node.put(JsonLdConsts.GRAPH, new ArrayList<Object>(4));
                final List<String> keys = new ArrayList<String>(graphMap.get(subject).keySet());
                Collections.sort(keys);
                for (final String s : keys) {
                    final NodeMapNode n = graphMap.get(subject).get(s);
                    if (n.size() == 1 && n.containsKey(JsonLdConsts.ID)) {
                        continue;
                    }
                    ((List<Object>) node.get(JsonLdConsts.GRAPH)).add(n.serialize());
                }
            }
            if (node.size() == 1 && node.containsKey(JsonLdConsts.ID)) {
                continue;
            }
            result.add(node.serialize());
        }
        return result;
    }

    /***
     * ____ _ _ ____ ____ _____ _ _ _ _ _ / ___|___ _ ____ _____ _ __| |_ | |_
     * ___ | _ \| _ \| ___| / \ | | __ _ ___ _ __(_) |_| |__ _ __ ___ | | / _ \|
     * '_ \ \ / / _ \ '__| __| | __/ _ \ | |_) | | | | |_ / _ \ | |/ _` |/ _ \|
     * '__| | __| '_ \| '_ ` _ \ | |__| (_) | | | \ V / __/ | | |_ | || (_) | |
     * _ <| |_| | _| / ___ \| | (_| | (_) | | | | |_| | | | | | | | |
     * \____\___/|_| |_|\_/ \___|_| \__| \__\___/ |_| \_\____/|_| /_/ \_\_|\__,
     * |\___/|_| |_|\__|_| |_|_| |_| |_| |___/
     */
    /**
     * Adds RDF triples for each graph in the current node map to an RDF
     * dataset.
     *
     * @return the RDF dataset.
     * @throws JsonLdError
     *             If there was an error converting from JSON-LD to RDF.
     */
    public RDFDataset toRDF() throws JsonLdError {
        final Map<String, Object> nodeMap = newMap();
        nodeMap.put(JsonLdConsts.DEFAULT, newMap());
        generateNodeMap(this.value, nodeMap);
        final RDFDataset dataset = new RDFDataset(this);
        for (final String graphName : nodeMap.keySet()) {
            if (JsonLdUtils.isRelativeIri(graphName)) {
                continue;
            }
            final Map<String, Object> graph = (Map<String, Object>) nodeMap.get(graphName);
            dataset.graphToRDF(graphName, graph);
        }
        return dataset;
    }

    /***
     * _ _ _ _ _ _ _ _ _ _ _ | \ | | ___ _ __ _ __ ___ __ _| (_)______ _| |_(_)
     * ___ _ __ / \ | | __ _ ___ _ __(_) |_| |__ _ __ ___ | \| |/ _ \| '__| '_ `
     * _ \ / _` | | |_ / _` | __| |/ _ \| '_ \ / _ \ | |/ _` |/ _ \| '__| | __|
     * '_ \| '_ ` _ \ | |\ | (_) | | | | | | | | (_| | | |/ / (_| | |_| | (_) |
     * | | | / ___ \| | (_| | (_) | | | | |_| | | | | | | | | |_| \_|\___/|_|
     * |_| |_| |_|\__,_|_|_/___\__,_|\__|_|\___/|_| |_| /_/ \_\_|\__, |\___/|_|
     * |_|\__|_| |_|_| |_| |_| |___/
     */
    /**
     * Performs RDF normalization on the given JSON-LD input.
     *
     * @param dataset
     *            the expanded JSON-LD object to normalize.
     * @return The normalized JSON-LD object
     * @throws JsonLdError
     *             If there was an error while normalizing.
     */
    public Object normalize(Map<String, Object> dataset) throws JsonLdError {
        final List<Object> quads = new ArrayList<Object>();
        final Map<String, Object> bnodes = newMap();
        for (String graphName : dataset.keySet()) {
            final List<Map<String, Object>> triples = (List<Map<String, Object>>) dataset.get(graphName);
            if (JsonLdConsts.DEFAULT.equals(graphName)) {
                graphName = null;
            }
            for (final Map<String, Object> quad : triples) {
                if (graphName != null) {
                    if (graphName.indexOf("_:") == 0) {
                        final Map<String, Object> tmp = newMap();
                        tmp.put("type", "blank node");
                        tmp.put("value", graphName);
                        quad.put("name", tmp);
                    } else {
                        final Map<String, Object> tmp = newMap();
                        tmp.put("type", "IRI");
                        tmp.put("value", graphName);
                        quad.put("name", tmp);
                    }
                }
                quads.add(quad);
                final String[] attrs = new String[] { "subject", "object", "name" };
                for (final String attr : attrs) {
                    if (quad.containsKey(attr) && "blank node".equals(((Map<String, Object>) quad.get(attr)).get("type"))) {
                        final String id = (String) ((Map<String, Object>) quad.get(attr)).get("value");
                        if (!bnodes.containsKey(id)) {
                            bnodes.put(id, new LinkedHashMap<String, List<Object>>() {

                                {
                                    put("quads", new ArrayList<Object>());
                                }
                            });
                        }
                        ((List<Object>) ((Map<String, Object>) bnodes.get(id)).get("quads")).add(quad);
                    }
                }
            }
        }
        final NormalizeUtils normalizeUtils = new NormalizeUtils(quads, bnodes, new UniqueNamer("_:c14n"), opts);
        return normalizeUtils.hashBlankNodes(bnodes.keySet());
    }
}
