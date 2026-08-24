package fengyu

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"reflect"
	"strings"
)

// Contract is the typed, code-first IR builder consumed by fengyu generate.
type Contract struct {
	pluginID string
	methods  map[string]any
	tools    []map[string]any
	origins  map[string]string
}

func NewContract(pluginID string) *Contract {
	return &Contract{pluginID: pluginID, methods: map[string]any{}, tools: []map[string]any{}, origins: map[string]string{}}
}

func (c *Contract) RPC(name, description string, input, output any, origin string) *Contract {
	c.methods[name] = map[string]any{"description": description, "inputSchema": schemaFor(reflect.TypeOf(input)), "outputSchema": schemaFor(reflect.TypeOf(output))}
	if origin != "" {
		c.origins["rpc.methods."+name] = origin
	}
	return c
}

func (c *Contract) AITool(name, method, description, effect string) *Contract {
	c.tools = append(c.tools, map[string]any{"name": name, "method": method, "description": description, "effect": effect})
	return c
}

func (c *Contract) IR() map[string]any {
	return map[string]any{"formatVersion": 1, "pluginId": c.pluginID, "rpc": map[string]any{"methods": c.methods}, "aiTools": c.tools, "origins": c.origins}
}

func (c *Contract) Write(pluginRoot string) error {
	output := filepath.Join(pluginRoot, "target", "fengyu-contract", "contract.json")
	if err := os.MkdirAll(filepath.Dir(output), 0o755); err != nil {
		return err
	}
	data, err := json.MarshalIndent(c.IR(), "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(output, append(data, '\n'), 0o644)
}

func schemaFor(value reflect.Type) map[string]any {
	for value.Kind() == reflect.Pointer {
		value = value.Elem()
	}
	switch value.Kind() {
	case reflect.String:
		return map[string]any{"type": "string"}
	case reflect.Bool:
		return map[string]any{"type": "boolean"}
	case reflect.Int, reflect.Int8, reflect.Int16, reflect.Int32, reflect.Int64,
		reflect.Uint, reflect.Uint8, reflect.Uint16, reflect.Uint32, reflect.Uint64:
		return map[string]any{"type": "integer"}
	case reflect.Float32, reflect.Float64:
		return map[string]any{"type": "number"}
	case reflect.Slice, reflect.Array:
		return map[string]any{"type": "array", "items": schemaFor(value.Elem())}
	case reflect.Map:
		if value.Key().Kind() != reflect.String {
			panic("FengYu contract map keys must be strings")
		}
		return map[string]any{"type": "object", "additionalProperties": schemaFor(value.Elem())}
	case reflect.Struct:
		properties, required := map[string]any{}, []string{}
		for index := 0; index < value.NumField(); index++ {
			field := value.Field(index)
			if !field.IsExported() {
				continue
			}
			parts := strings.Split(field.Tag.Get("json"), ",")
			name := parts[0]
			if name == "-" {
				continue
			}
			if name == "" {
				name = field.Name
			}
			property := schemaFor(field.Type)
			if description := field.Tag.Get("description"); description != "" {
				property["description"] = description
			}
			if title := field.Tag.Get("title"); title != "" {
				property["title"] = title
			}
			if rawDefault := field.Tag.Get("default"); rawDefault != "" {
				var parsed any
				if json.Unmarshal([]byte(rawDefault), &parsed) != nil {
					parsed = rawDefault
				}
				property["default"] = parsed
			}
			properties[name] = property
			optional := field.Type.Kind() == reflect.Pointer
			for _, option := range parts[1:] {
				optional = optional || option == "omitempty"
			}
			if !optional {
				required = append(required, name)
			}
		}
		schema := map[string]any{"type": "object", "properties": properties}
		if len(required) > 0 {
			schema["required"] = required
		}
		return schema
	case reflect.Interface:
		return map[string]any{}
	default:
		panic(fmt.Sprintf("unsupported FengYu contract type: %s", value))
	}
}
