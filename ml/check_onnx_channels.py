import onnx

def check_model(path):
    try:
        model = onnx.load(path)
        print(f"Model: {path}")
        
        print("\nInputs:")
        for input in model.graph.input:
            shape = []
            for dim in input.type.tensor_type.shape.dim:
                shape.append(dim.dim_value if dim.dim_value > 0 else "None")
            print(f"  Name: {input.name}, Shape: {shape}")
            
        print("\nOutputs:")
        for output in model.graph.output:
            shape = []
            for dim in output.type.tensor_type.shape.dim:
                shape.append(dim.dim_value if dim.dim_value > 0 else "None")
            print(f"  Name: {output.name}, Shape: {shape}")
            
    except Exception as e:
        print(f"Error: {e}")

if __name__ == "__main__":
    check_model("experiments/outputs/ssgo_hyper/ssgo_robust_medium.onnx")
