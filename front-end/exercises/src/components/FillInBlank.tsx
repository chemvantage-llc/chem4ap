import React from 'react';

interface FillInBlankProps {
    handleSubmit: (event: React.FormEvent<HTMLFormElement>) => void;
    setStudentAnswer: (studentAnswer: string) => void;
}

const FillInBlank: React.FC<FillInBlankProps> = ({ handleSubmit, setStudentAnswer }) => {
    return (
        <form onSubmit={handleSubmit}>
            <label>
                Your Answer:&nbsp;
                <input 
                    type="text" 
                    required 
                    onChange={(e) => setStudentAnswer(e.target.value)}
                    style={{ width: "150px" }} 
                />
            </label>&nbsp;
            <button className="btn btn-primary" type="submit">Submit</button>
        </form>
    );
}

export default FillInBlank;