import jax
import jax.numpy as jnp
import numpy as np
import optax
from flax.training import train_state
from brml.models.lod_classifier import LODClassifier
import os

def train_lod():
    print("═══ Training LOD Classifier (Trial) ═══")
    model = LODClassifier(in_features=14, hidden_dim=32, num_classes=4)
    rng = jax.random.PRNGKey(42)
    
    # 1. Initialize
    dummy_input = jnp.zeros((1, 14))
    params = model.init(rng, dummy_input)["params"]
    
    # 2. Setup Optimizer
    opt = optax.adam(1e-3)
    state = train_state.TrainState.create(apply_fn=model.apply, params=params, tx=opt)
    
    # 3. Synthetic Data for Trial
    # In a real run, this would be statistics from chunks
    n_samples = 1000
    x_train = np.random.randn(n_samples, 14).astype(np.float32)
    y_train = np.random.randint(0, 4, size=(n_samples,))
    
    @jax.jit
    def train_step(state, x, y):
        def loss_fn(p):
            logits = state.apply_fn({"params": p}, x)
            one_hot = jax.nn.one_hot(y, 4)
            return -jnp.mean(jnp.sum(one_hot * jax.nn.log_softmax(logits), axis=-1))
        
        loss, grads = jax.value_and_grad(loss_fn)(state.params)
        return state.apply_gradients(grads=grads), loss

    # 4. Training Loop
    epochs = 50
    batch_size = 32
    for epoch in range(epochs):
        perm = np.random.permutation(n_samples)
        losses = []
        for i in range(0, n_samples, batch_size):
            idx = perm[i:i+batch_size]
            state, loss = train_step(state, x_train[idx], y_train[idx])
            losses.append(loss)
        if epoch % 10 == 0:
            print(f"  Epoch {epoch} loss: {np.mean(losses):.4f}")

    # 5. Export to ONNX
    print("Exporting LOD model to ONNX...")
    import tf2onnx
    import tensorflow as tf
    from jax.experimental import jax2tf
    
    # Convert JAX function to TF function
    tf_fn = jax2tf.convert(
        lambda x: model.apply({"params": state.params}, x),
        enable_xla=False
    )
    
    # Create a TF model
    class TFModel(tf.Module):
        @tf.function(input_signature=[tf.TensorSpec([1, 14], tf.float32, name="input")])
        def __call__(self, x):
            return tf_fn(x)
            
    tf_model = TFModel()
    
    out_dir = "/mnt/c/Users/wmc02/Desktop/git/Block-Realityapi-Fast-design-1/ml/experiments/outputs/trial_lod"
    os.makedirs(out_dir, exist_ok=True)
    onnx_path = os.path.join(out_dir, "lod_classifier.onnx")
    
    # Convert to ONNX
    model_proto, _ = tf2onnx.convert.from_function(
        tf_model.__call__,
        input_signature=[tf.TensorSpec([1, 14], tf.float32, name="input")],
        opset=17,
        output_path=onnx_path
    )
    print(f"  Saved LOD ONNX to {onnx_path}")

if __name__ == "__main__":
    train_lod()
